"""Persisted telemetry samples (SQLite, 48 h) and derived insights for /api/status.

One row per poll (~30 s). Gaps longer than MAX_GAP_SECONDS (service down, device silent)
are never bridged: they are excluded from averages, coverage and energy integrals.
"""
from __future__ import annotations

import math
import os
import sqlite3
import threading
from datetime import datetime
from zoneinfo import ZoneInfo

KYIV = ZoneInfo("Europe/Kyiv")
RETENTION_SECONDS = 48 * 3600
WINDOW_SECONDS = 24 * 3600
MAX_GAP_SECONDS = 300.0
MIN_AVG_COVERAGE_SECONDS = 3600.0
MIN_DRAW_COVERAGE_SECONDS = 1800.0
BACKUP_DRAW_THRESHOLD_W = 5.0
SOC_BUCKET_SECONDS = 1800
SOC_MAX_POINTS = 48
COLUMNS = ("ts", "soc", "bat_wh", "flow_w", "flow_dir", "offgrid_w", "ongrid_w", "temp_c")
EVENT_RETENTION_SECONDS = 30 * 86400
EVENT_COLUMNS = ("ts", "kind", "category", "text", "value")


class HistoryStore:
    def __init__(self, path, retention_seconds=RETENTION_SECONDS, prune_every=120):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self.retention_seconds = retention_seconds
        self.prune_every = prune_every
        self._inserts = 0
        self.lock = threading.Lock()
        self.conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA synchronous=NORMAL")
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS samples (ts REAL PRIMARY KEY, soc INTEGER, bat_wh REAL, flow_w REAL,"
            " flow_dir TEXT, offgrid_w REAL, ongrid_w REAL, temp_c REAL) WITHOUT ROWID"
        )
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL,"
            " kind TEXT NOT NULL, category TEXT NOT NULL, text TEXT NOT NULL, value REAL)"
        )
        self.conn.execute("CREATE INDEX IF NOT EXISTS events_ts ON events (ts)")

    def record(self, sample: dict):
        values = tuple(sample.get(name) for name in COLUMNS)
        with self.lock:
            self.conn.execute(f"INSERT OR REPLACE INTO samples ({','.join(COLUMNS)}) VALUES (?,?,?,?,?,?,?,?)", values)
            self._inserts += 1
            due = self._inserts % self.prune_every == 0
        if due:
            self.prune(sample["ts"])

    def prune(self, now):
        with self.lock:
            self.conn.execute("DELETE FROM samples WHERE ts < ?", (now - self.retention_seconds,))
            self.conn.execute("DELETE FROM events WHERE ts < ?", (now - EVENT_RETENTION_SECONDS,))

    def record_event(self, event: dict):
        values = tuple(event.get(name) for name in EVENT_COLUMNS)
        with self.lock:
            self.conn.execute(f"INSERT INTO events ({','.join(EVENT_COLUMNS)}) VALUES (?,?,?,?,?)", values)

    def events(self, limit=200, category=None, since=None):
        """Newest first. `category`: light / battery / connection / anomaly."""
        query = f"SELECT {','.join(EVENT_COLUMNS)} FROM events"
        where, args = [], []
        if category:
            where.append("category = ?")
            args.append(category)
        if since is not None:
            where.append("ts >= ?")
            args.append(float(since))
        if where:
            query += " WHERE " + " AND ".join(where)
        query += " ORDER BY ts DESC, id DESC LIMIT ?"
        args.append(max(1, min(1000, int(limit))))
        with self.lock:
            return [dict(zip(EVENT_COLUMNS, values)) for values in self.conn.execute(query, args).fetchall()]

    def since(self, start_ts):
        with self.lock:
            cursor = self.conn.execute(f"SELECT {','.join(COLUMNS)} FROM samples WHERE ts >= ? ORDER BY ts", (start_ts,))
            return [dict(zip(COLUMNS, values)) for values in cursor.fetchall()]

    def close(self):
        with self.lock:
            self.conn.close()


def _finite(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _intervals(rows, start_ts, key="offgrid_w"):
    """Yield (t0, t1, v0, v1) for consecutive valid samples within [start_ts, ...] and no long gap."""
    points = [(r["ts"], _finite(r.get(key))) for r in rows if r.get("ts") is not None and r["ts"] >= start_ts]
    points = [(t, v) for t, v in points if v is not None]
    points.sort()
    for (t0, v0), (t1, v1) in zip(points, points[1:]):
        if 0 < t1 - t0 <= MAX_GAP_SECONDS:
            yield t0, t1, v0, v1


def _integral(intervals):
    energy_ws = covered = 0.0
    for t0, t1, v0, v1 in intervals:
        energy_ws += (abs(v0) + abs(v1)) / 2.0 * (t1 - t0)
        covered += t1 - t0
    return energy_ws, covered


def measured_backup_load(rows, *, now):
    """Mean backup-output draw over the last 24 h, only while the backup output was actually drawing.

    With grid present the Venus bypasses the backup loads and reports ~0 W, so idle time is
    excluded instead of diluting the average. None until 30 min of draw has been observed.
    """
    draw = [iv for iv in _intervals(rows, now - WINDOW_SECONDS)
            if abs(iv[2]) >= BACKUP_DRAW_THRESHOLD_W and abs(iv[3]) >= BACKUP_DRAW_THRESHOLD_W]
    energy_ws, covered = _integral(draw)
    if covered < MIN_DRAW_COVERAGE_SECONDS:
        return None
    return round(energy_ws / covered, 1)


def _local_midnight(now):
    local = datetime.fromtimestamp(now, KYIV)
    return datetime(local.year, local.month, local.day, tzinfo=KYIV).timestamp()


def _soc_history(rows, now):
    start = now - WINDOW_SECONDS
    buckets = {}
    for r in rows:
        t, soc = r.get("ts"), _finite(r.get("soc"))
        if t is None or soc is None or t < start or t > now:
            continue
        key = int(t // SOC_BUCKET_SECONDS)
        if key not in buckets or t >= buckets[key][0]:
            buckets[key] = (t, soc)
    points = [buckets[key] for key in sorted(buckets)][-SOC_MAX_POINTS:]
    return [{"t": datetime.fromtimestamp(round(t), KYIV).isoformat(timespec="seconds"), "soc": int(round(soc))}
            for t, soc in points]


def _state_intervals(rows, start_ts, now):
    """(t0, t1, row0) for consecutive samples ≤ MAX_GAP apart, clipped to [start_ts, now]. State = earlier sample."""
    points = sorted((r for r in rows if r.get("ts") is not None), key=lambda r: r["ts"])
    for a, b in zip(points, points[1:]):
        t0, t1 = a["ts"], b["ts"]
        if not 0 < t1 - t0 <= MAX_GAP_SECONDS:
            continue
        t0, t1 = max(t0, start_ts), min(t1, now)
        if t1 > t0:
            yield t0, t1, a


def _is_outage(row, threshold_w):
    value = _finite(row.get("offgrid_w"))
    return value is not None and abs(value) >= threshold_w


def today_durations(rows, *, now, threshold_w=BACKUP_DRAW_THRESHOLD_W):
    """Seconds since local midnight spent charging / discharging / without grid (backup output drawing).

    Gaps longer than MAX_GAP_SECONDS (no samples) count as no state at all."""
    charge = discharge = outage = 0.0
    for t0, t1, sample in _state_intervals(rows, _local_midnight(now), now):
        span = t1 - t0
        if sample.get("flow_dir") == "charging":
            charge += span
        elif sample.get("flow_dir") == "discharging":
            discharge += span
        if _is_outage(sample, threshold_w):
            outage += span
    return {"charge_seconds": round(charge), "discharge_seconds": round(discharge), "outage_seconds": round(outage)}


def outage_periods(rows, *, now, threshold_w=BACKUP_DRAW_THRESHOLD_W):
    """Backup-output draw periods over the last 24 h as [{start, end}] (ISO, Kyiv); gaps split periods."""
    periods = []
    for t0, t1, sample in _state_intervals(rows, now - WINDOW_SECONDS, now):
        if not _is_outage(sample, threshold_w):
            continue
        if periods and abs(periods[-1][1] - t0) < 1e-6:
            periods[-1][1] = t1
        else:
            periods.append([t0, t1])
    iso = lambda t: datetime.fromtimestamp(round(t), KYIV).isoformat(timespec="seconds")  # noqa: E731
    return [{"start": iso(a), "end": iso(b)} for a, b in periods if b - a >= 60]


def compute_insights(rows, *, now, current_offgrid_w, temperature_c, threshold_w=BACKUP_DRAW_THRESHOLD_W,
                     chart_events=None):
    energy_ws, covered = _integral(_intervals(rows, now - WINDOW_SECONDS))
    average = round(energy_ws / covered, 1) if covered >= MIN_AVG_COVERAGE_SECONDS else None
    today = list(_intervals(rows, _local_midnight(now)))
    today_ws, _ = _integral(today)
    offgrid = _finite(current_offgrid_w)
    temp = _finite(temperature_c)
    return {
        "backup_output_w": None if offgrid is None else float(offgrid),
        "backup_avg_w_24h": average,
        "backup_avg_coverage_hours": round(covered / 3600.0, 2),
        "backup_energy_today_wh": round(today_ws / 3600.0, 1) if today else None,
        "temperature_c": None if temp is None else float(temp),
        "soc_history": _soc_history(rows, now),
        "today": today_durations(rows, now=now, threshold_w=threshold_w),
        "outage_periods": outage_periods(rows, now=now, threshold_w=threshold_w),
        "chart_events": list(chart_events or []),
    }

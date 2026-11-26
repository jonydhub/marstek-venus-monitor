"""Persisted telemetry samples (SQLite, 8 days) and derived insights for /api/status.

One row per poll (~15 s). A poll the battery did not answer is stored too, with only `ts` (or without the ES
powers) — it breaks the light-return hysteresis run (see gridstate.py). Gaps longer than MAX_GAP_SECONDS
(service down, device silent) are never bridged: they are excluded from averages, coverage and energy integrals.
"""
from __future__ import annotations

import math
import os

import gridstate
import sqlite3
import threading
from datetime import datetime
from zoneinfo import ZoneInfo

KYIV = ZoneInfo("Europe/Kyiv")
RETENTION_SECONDS = 8 * 86400  # 7-day load statistics for the high-load alert + 1 day margin
LOAD_STATS_WINDOW_SECONDS = 7 * 86400
SMOOTH_WINDOW_SECONDS = 600.0
MEASURED_MIN_W = 10.0
GRID_CHARGE_W = gridstate.GRID_CHARGE_W
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
        self.conn.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
        # Per-day totals of completed days, kept forever (samples are pruned after 8 days) — the «Лічильники» total.
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS day_totals (date TEXT PRIMARY KEY, charge_wh REAL NOT NULL,"
            " discharge_wh REAL NOT NULL, outage_energy_wh REAL NOT NULL, off_seconds REAL NOT NULL,"
            " outages INTEGER NOT NULL) WITHOUT ROWID"
        )

    def get_meta(self, key, default=None):
        """Small persisted per-install facts (e.g. the learned «bypass load seen» flag); never pruned."""
        with self.lock:
            row = self.conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row[0] if row else default

    def set_meta(self, key, value):
        with self.lock:
            self.conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", (key, str(value)))

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
        """Newest first. `category`: light / battery / connection / anomaly / system."""
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

    def first_event_ts(self):
        """Oldest kept event (journal start within the 30-day retention), or None."""
        with self.lock:
            row = self.conn.execute("SELECT MIN(ts) FROM events").fetchone()
        return row[0] if row and row[0] is not None else None

    def since(self, start_ts):
        with self.lock:
            cursor = self.conn.execute(f"SELECT {','.join(COLUMNS)} FROM samples WHERE ts >= ? ORDER BY ts", (start_ts,))
            return [dict(zip(COLUMNS, values)) for values in cursor.fetchall()]

    def first_sample_ts(self):
        with self.lock:
            row = self.conn.execute("SELECT MIN(ts) FROM samples").fetchone()
        return row[0] if row and row[0] is not None else None

    def save_day_totals(self, days):
        """Upsert completed days of stats()["days"] (dicts with date / charge_wh / discharge_wh / outage_energy_wh /
        off_seconds / outages). Callers pass only fully covered past days — today is never stored."""
        with self.lock:
            self.conn.executemany(
                "INSERT OR REPLACE INTO day_totals (date, charge_wh, discharge_wh, outage_energy_wh, off_seconds, outages)"
                " VALUES (?,?,?,?,?,?)",
                [(d["date"], d["charge_wh"], d["discharge_wh"], d["outage_energy_wh"], d["off_seconds"], d["outages"])
                 for d in days])

    def day_totals(self, before_date=None):
        """Stored days (oldest first), optionally only those before `before_date` (ISO)."""
        query = "SELECT date, charge_wh, discharge_wh, outage_energy_wh, off_seconds, outages FROM day_totals"
        args = ()
        if before_date:
            query += " WHERE date < ?"
            args = (before_date,)
        with self.lock:
            rows = self.conn.execute(query + " ORDER BY date", args).fetchall()
        keys = ("date", "charge_wh", "discharge_wh", "outage_energy_wh", "off_seconds", "outages")
        return [dict(zip(keys, r)) for r in rows]

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
        energy_ws += (max(0.0, v0) + max(0.0, v1)) / 2.0 * (t1 - t0)  # only positive = backup load
        covered += t1 - t0
    return energy_ws, covered


def measured_backup_load(rows, *, now):
    """Mean backup-output draw over the last 24 h, only while the backup output was actually drawing.

    With grid present the Venus bypasses the backup loads and reports ~0 W, so idle time is
    excluded instead of diluting the average. None until 30 min of draw has been observed.
    """
    draw = [iv for iv in _intervals(rows, now - WINDOW_SECONDS)
            if iv[2] >= BACKUP_DRAW_THRESHOLD_W and iv[3] >= BACKUP_DRAW_THRESHOLD_W]
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
    return value is not None and value >= threshold_w  # signed: negative offgrid is noise, not a load


def _measured_battery_w(row):
    """Battery power from the AC ports (+ charging, − discharging) like server._signed_ac_battery_power, or None."""
    ongrid, offgrid = _finite(row.get("ongrid_w")), _finite(row.get("offgrid_w"))
    ongrid = ongrid if ongrid is not None and abs(ongrid) >= MEASURED_MIN_W else 0.0
    offgrid = offgrid if offgrid is not None and offgrid >= MEASURED_MIN_W else 0.0
    if not ongrid and not offgrid:
        return None
    return -ongrid - offgrid


def grid_timeline(rows, *, start, now, threshold_w=BACKUP_DRAW_THRESHOLD_W,
                  confirm_gap=gridstate.DEFAULT_CONFIRM_GAP_SECONDS, bypass="auto", bypass_seen=False):
    """Confirmed light on/off/unknown spans (epoch seconds) over [start, now] — see gridstate.timeline.

    ``bypass`` / ``bypass_seen``: MARSTEK_BYPASS_LOAD and the learned flag (see gridstate, «Bypass»)."""
    return gridstate.timeline(rows, start=start, now=now, threshold_w=threshold_w, confirm_gap=confirm_gap,
                              max_gap=MAX_GAP_SECONDS, bypass=bypass, bypass_seen=bypass_seen)


def _iso(t):
    return datetime.fromtimestamp(round(t), KYIV).isoformat(timespec="seconds")


def today_durations(rows, *, now, threshold_w=BACKUP_DRAW_THRESHOLD_W,
                    confirm_gap=gridstate.DEFAULT_CONFIRM_GAP_SECONDS, bypass="auto", bypass_seen=False):
    """Since local midnight: seconds charging / discharging / without light, energy in/out of the battery (Wh).

    «Без світла» = confirmed OFF time of the light timeline (a compressor pause is still an outage). Energy comes from
    the measured AC powers; where both read 0 the capacity-trend estimate is used and `*_estimated` says so.
    Gaps longer than MAX_GAP_SECONDS (no samples) count as no state at all."""
    midnight = _local_midnight(now)
    charge = discharge = 0.0
    energy = {"charge": 0.0, "discharge": 0.0, "charge_est": 0.0, "discharge_est": 0.0}
    for t0, t1, sample in _state_intervals(rows, midnight, now):
        span = t1 - t0
        if sample.get("flow_dir") == "charging":
            charge += span
        elif sample.get("flow_dir") == "discharging":
            discharge += span
        power, estimated = _measured_battery_w(sample), False
        if power is None and sample.get("flow_dir") in ("charging", "discharging"):
            power, estimated = _finite(sample.get("flow_w")), True
        if power:
            key = "charge" if power > 0 else "discharge"
            wh = abs(power) * span / 3600.0
            energy[key] += wh
            if estimated:
                energy[key + "_est"] += wh
    timeline = grid_timeline(rows, start=midnight, now=now, threshold_w=threshold_w, confirm_gap=confirm_gap,
                             bypass=bypass, bypass_seen=bypass_seen)
    outage = sum(s["end"] - s["start"] for s in timeline if s["state"] == "off")
    outages = outage_details(rows, start=midnight, now=now, timeline=timeline)
    return {"charge_seconds": round(charge), "discharge_seconds": round(discharge), "outage_seconds": round(outage),
            "charge_wh": round(energy["charge"], 1), "discharge_wh": round(energy["discharge"], 1),
            "charge_estimated": energy["charge_est"] > 1.0, "discharge_estimated": energy["discharge_est"] > 1.0,
            "outage_energy_wh": round(sum(o["energy_wh"] for o in outages), 1), "outages": outages,
            "grid_timeline": [{"start": _iso(s["start"]), "end": _iso(s["end"]), "state": s["state"]} for s in timeline]}


WEEK_DAYS = 7
CHARGE_JOIN_SECONDS = 600.0   # charging runs closer than this are one charge session
CHARGE_MIN_WH = 100.0         # a recharge: ≥ this much energy or ≥ CHARGE_MIN_SOC % — not a standby top-up 99→100 %
CHARGE_MIN_SOC = 3
OUTAGE_MIN_SECONDS = 60.0


class _SocIndex:
    """Sorted (ts, soc) of the samples with a SOC, for O(log n) lookups (stats run over ~40 000 samples)."""

    def __init__(self, rows):
        pts = sorted((r["ts"], soc) for r in rows
                     if r.get("ts") is not None and (soc := _finite(r.get("soc"))) is not None)
        self.ts = [p[0] for p in pts]
        self.soc = [p[1] for p in pts]

    def near(self, t, after=True):
        """SOC of the first sample at/after `t` (or the last one at/before it), within MAX_GAP_SECONDS."""
        import bisect
        if after:
            i = bisect.bisect_left(self.ts, t)
            ok = i < len(self.ts) and self.ts[i] <= t + MAX_GAP_SECONDS
        else:
            i = bisect.bisect_right(self.ts, t) - 1
            ok = i >= 0 and self.ts[i] >= t - MAX_GAP_SECONDS
        return int(round(self.soc[i])) if ok else None


def _soc_near(rows, t, after=True, index=None):
    """SOC of the first sample at/after `t` (or the last one at/before it), within MAX_GAP_SECONDS."""
    return (index or _SocIndex(rows)).near(t, after)


def _backup_wh(rows, a, b):
    """Energy the station delivered on its backup output over [a, b] (Wh, step integral of offgrid_w ≥ 0)."""
    ws = 0.0
    for t0, t1, row in _state_intervals(rows, a, b):
        w = _finite(row.get("offgrid_w"))
        if w is not None and w > 0:
            ws += w * (t1 - t0)
    return ws / 3600.0


def outage_details(rows, *, start, now, threshold_w=BACKUP_DRAW_THRESHOLD_W,
                   confirm_gap=gridstate.DEFAULT_CONFIRM_GAP_SECONDS, bypass="auto", bypass_seen=False, timeline=None):
    """Measured outages over [start, now], oldest first: start/end (ISO), seconds, energy from the station (Wh, backup
    output), average W, SOC at start/end, ``ongoing`` for the one still running at ``now``. Same light rules as the
    «Фактично» row (one timeline; a short unknown gap inside an outage splits it, as on the row)."""
    if timeline is None:
        timeline = grid_timeline(rows, start=start, now=now, threshold_w=threshold_w, confirm_gap=confirm_gap,
                                 bypass=bypass, bypass_seen=bypass_seen)
    out, idx = [], _SocIndex(rows)
    for s in timeline:
        if s["state"] != "off" or s["end"] - s["start"] < OUTAGE_MIN_SECONDS:
            continue
        a, b = s["start"], s["end"]
        wh = _backup_wh(rows, a, b)
        seconds = b - a
        out.append({"start": _iso(a), "end": _iso(b), "seconds": round(seconds), "energy_wh": round(wh, 1),
                    "avg_w": round(wh * 3600.0 / seconds) if seconds > 0 and wh > 0 else None,
                    "soc_start": idx.near(a, after=True), "soc_end": idx.near(b, after=False),
                    "ongoing": now - b < MAX_GAP_SECONDS})
    return out


def charge_sessions(rows, *, start, now):
    """Battery charge sessions over [start, now], oldest first: start/end, seconds, energy in (Wh; measured AC power,
    else the capacity-trend estimate), SOC from/to, ``estimated`` when part of it is the estimate. Runs closer than
    CHARGE_JOIN_SECONDS merge; standby top-ups (< CHARGE_MIN_WH and < CHARGE_MIN_SOC %) are left out."""
    sessions = []
    for t0, t1, row in _state_intervals(rows, start, now):
        if row.get("flow_dir") != "charging":
            continue
        power, estimated = _measured_battery_w(row), False
        if power is None or power <= 0:
            power, estimated = _finite(row.get("flow_w")), True
        wh = max(0.0, power or 0.0) * (t1 - t0) / 3600.0
        if sessions and t0 - sessions[-1]["b"] <= CHARGE_JOIN_SECONDS:
            cur = sessions[-1]
            cur["b"] = t1
            cur["wh"] += wh
            cur["est"] += wh if estimated else 0.0
            cur["active"] += t1 - t0
        else:
            sessions.append({"a": t0, "b": t1, "wh": wh, "est": wh if estimated else 0.0, "active": t1 - t0})
    out, idx = [], _SocIndex(rows)
    for c in sessions:
        soc_a, soc_b = idx.near(c["a"], after=True), idx.near(c["b"], after=False)
        rise = soc_b - soc_a if soc_a is not None and soc_b is not None else 0
        if c["wh"] < CHARGE_MIN_WH and rise < CHARGE_MIN_SOC:
            continue
        out.append({"start": _iso(c["a"]), "end": _iso(c["b"]), "seconds": round(c["b"] - c["a"]),
                    "energy_wh": round(c["wh"], 1), "avg_w": round(c["wh"] * 3600.0 / c["active"]) if c["active"] else None,
                    "soc_start": soc_a, "soc_end": soc_b,
                    "estimated": c["est"] > 0.5 * c["wh"], "ongoing": now - c["b"] < MAX_GAP_SECONDS})
    return out


def stats(rows, *, now, days=WEEK_DAYS, threshold_w=BACKUP_DRAW_THRESHOLD_W,
          confirm_gap=gridstate.DEFAULT_CONFIRM_GAP_SECONDS, bypass="auto", bypass_seen=False):
    """«Статистика» for today and the previous days: per-day totals, the outages and the charge sessions (newest
    first). One light timeline over the whole window, as the week view."""
    starts = day_starts(now, days)
    first = starts[-1]
    timeline = grid_timeline(rows, start=first, now=now, threshold_w=threshold_w, confirm_gap=confirm_gap,
                             bypass=bypass, bypass_seen=bypass_seen)
    outages = outage_details(rows, start=first, now=now, timeline=timeline)
    charges = charge_sessions(rows, start=first, now=now)
    off_spans = [(s["start"], s["end"]) for s in timeline if s["state"] == "off"]
    per_day = []
    for i, a in enumerate(starts):
        b = now if i == 0 else starts[i - 1]
        off = sum(max(0.0, min(s["end"], b) - max(s["start"], a)) for s in timeline if s["state"] == "off")
        backup_wh = charge_wh = discharge_wh = 0.0
        for t0, t1, row in _state_intervals(rows, a, b):
            span = t1 - t0
            w = _finite(row.get("offgrid_w"))
            light_off = any(x <= t0 < y for x, y in off_spans)
            if light_off and w is not None and w > 0:
                backup_wh += w * span / 3600.0
            power = _measured_battery_w(row)
            if power is None and row.get("flow_dir") in ("charging", "discharging"):
                power = _finite(row.get("flow_w"))
            if power:
                if power > 0:
                    charge_wh += power * span / 3600.0
                else:
                    discharge_wh += -power * span / 3600.0
        day_iso = datetime.fromtimestamp(a, KYIV).date().isoformat()
        day_outages = [o for o in outages if o["start"][:10] == day_iso]
        per_day.append({"date": day_iso, "is_today": i == 0, "off_seconds": round(off), "outages": len(day_outages),
                        "outage_energy_wh": round(backup_wh, 1), "charge_wh": round(charge_wh, 1),
                        "discharge_wh": round(discharge_wh, 1)})
    return {"days": per_day, "outages": outages[::-1], "charges": charges[::-1]}


COUNTER_KEYS = ("charge_wh", "discharge_wh", "outage_energy_wh", "off_seconds", "outages")


def _sum_days(days):
    total = {k: sum(d.get(k) or 0 for d in days) for k in COUNTER_KEYS}
    return {k: round(v) if k in ("off_seconds", "outages") else round(v, 1) for k, v in total.items()}


def counters(store, days, *, first_sample_ts=None):
    """«Лічильники»: today / the stats window / everything since the server started keeping history.

    `days` = stats()["days"] (newest first). Completed days that have samples are saved to the never-pruned
    day_totals table on every call, so the total survives the 8-day sample retention and always matches «Статистика»
    for the days still in the window."""
    if not days:
        return None
    first_date = datetime.fromtimestamp(first_sample_ts, KYIV).date().isoformat() if first_sample_ts else None
    past = [d for d in days[1:] if first_date is None or d["date"] >= first_date]
    if store is not None and past:
        store.save_day_totals(past)
    stored = store.day_totals(before_date=days[0]["date"]) if store is not None else past
    covered = [d for d in days if first_date is None or d["date"] >= first_date]
    since = stored[0]["date"] if stored else days[0]["date"]
    return {"since": since, "window_days": len(covered),
            "today": _sum_days(days[:1]), "window": _sum_days(covered), "total": _sum_days(stored + days[:1])}


def day_starts(now, days=WEEK_DAYS):
    """Kyiv midnights of today and the previous days, newest first (DST-safe: 23/25 h days)."""
    from datetime import timedelta
    today = datetime.fromtimestamp(now, KYIV).date()
    return [datetime.combine(today - timedelta(days=k), datetime.min.time(), KYIV).timestamp() for k in range(days)]


def week_light(rows, *, now, days=WEEK_DAYS, threshold_w=BACKUP_DRAW_THRESHOLD_W,
               confirm_gap=gridstate.DEFAULT_CONFIRM_GAP_SECONDS, bypass="auto", bypass_seen=False):
    """The «Фактично» light timeline for today and the previous days (newest first), one entry per Kyiv day.

    Same rules as today's row (one timeline over the whole window, so a state crossing midnight is not reset), then
    split at midnights. ``unknown_seconds`` = no data (server down, battery silent, before the first sample); today
    ends at ``now``."""
    starts = day_starts(now, days)
    timeline = grid_timeline(rows, start=starts[-1], now=now, threshold_w=threshold_w, confirm_gap=confirm_gap,
                             bypass=bypass, bypass_seen=bypass_seen)
    out = []
    for i, a in enumerate(starts):
        b = now if i == 0 else starts[i - 1]
        spans, totals = [], {"on": 0.0, "off": 0.0, "unknown": 0.0}
        for s in timeline:
            x, y = max(s["start"], a), min(s["end"], b)
            if y > x:
                spans.append({"start": _iso(x), "end": _iso(y), "state": s["state"]})
                totals[s["state"] if s["state"] in totals else "unknown"] += y - x
        day_end = starts[i - 1] if i else datetime.fromtimestamp(a + 30 * 3600, KYIV).replace(
            hour=0, minute=0, second=0, microsecond=0).timestamp()
        out.append({"date": datetime.fromtimestamp(a, KYIV).date().isoformat(), "is_today": i == 0,
                    "start": _iso(a), "end": _iso(day_end),
                    "off_seconds": round(totals["off"]), "on_seconds": round(totals["on"]),
                    "unknown_seconds": round(totals["unknown"]), "timeline": spans})
    return out


def outage_periods(rows, *, now, threshold_w=BACKUP_DRAW_THRESHOLD_W,
                   confirm_gap=gridstate.DEFAULT_CONFIRM_GAP_SECONDS, bypass="auto", bypass_seen=False):
    """Confirmed no-light periods over the last 24 h as [{start, end}] (ISO, Kyiv); unknown gaps split periods."""
    spans = grid_timeline(rows, start=now - WINDOW_SECONDS, now=now, threshold_w=threshold_w, confirm_gap=confirm_gap,
                          bypass=bypass, bypass_seen=bypass_seen)
    return [{"start": _iso(s["start"]), "end": _iso(s["end"])} for s in spans
            if s["state"] == "off" and s["end"] - s["start"] >= 60]


def smoothed_backup_load(rows, *, now, since, window=SMOOTH_WINDOW_SECONDS):
    """Energy-weighted mean backup-output draw (W) over the last `window` s of the current outage (from `since`).

    Pauses of the load (0 W) count — that is the point: the runtime follows the average, not the last reading.
    None with fewer than 2 samples or no covered time."""
    if since is None:
        return None
    start = max(float(since), now - window)
    points = sorted((r["ts"], max(0.0, v)) for r in rows
                    if r.get("ts") is not None and start <= r["ts"] <= now and (v := _finite(r.get("offgrid_w"))) is not None)
    if len(points) < 2:
        return None
    energy = covered = 0.0
    for (t0, v0), (t1, v1) in zip(points, points[1:]):
        if 0 < t1 - t0 <= MAX_GAP_SECONDS:
            energy += (v0 + v1) / 2.0 * (t1 - t0)
            covered += t1 - t0
    if covered <= 0:
        return None
    return round(energy / covered, 1)


def backup_load_stats(rows, *, now, window=LOAD_STATS_WINDOW_SECONDS, threshold_w=BACKUP_DRAW_THRESHOLD_W):
    """(mean W, covered s) of the backup-output draw over `window`, only while it actually drew (zero-load excluded)."""
    draw = [iv for iv in _intervals(rows, now - window)
            if iv[2] >= threshold_w and iv[3] >= threshold_w]
    energy_ws, covered = _integral(draw)
    if covered <= 0:
        return None, 0.0
    return round(energy_ws / covered, 1), round(covered)


def last_grid_charge(rows, *, now, threshold_w=BACKUP_DRAW_THRESHOLD_W):
    """Last poll that charged from the grid (UPS top-up / recharge): {w, t}, or None within 24 h."""
    for r in sorted((r for r in rows if r.get("ts") is not None and now - WINDOW_SECONDS <= r["ts"] <= now),
                    key=lambda r: r["ts"], reverse=True):
        ongrid, offgrid = _finite(r.get("ongrid_w")), _finite(r.get("offgrid_w"))
        if ongrid is not None and ongrid < GRID_CHARGE_W and (offgrid is None or offgrid < threshold_w):
            return {"w": round(-ongrid), "t": _iso(r["ts"])}
    return None


STANDBY_MAX_INTERVAL_SECONDS = 6 * 3600


def standby_consumption(rows, *, now, threshold_w=BACKUP_DRAW_THRESHOLD_W):
    """Standby self-consumption (W) from the top-up cadence while held at 100 % with the grid present.

    The battery drops one SOC step (Δ bat_wh ≈ rated/100) between the end of one top-up (back at 100 %) and the
    next drop to 99 %; Wh / hours of that interval ≈ the standby draw. Median of the last 24 h, None if < 1 cycle.
    """
    points = sorted((r for r in rows if r.get("ts") is not None and now - WINDOW_SECONDS <= r["ts"] <= now),
                    key=lambda r: r["ts"])
    estimates, back_at, back_wh = [], None, None
    for prev, row in zip(points, points[1:]):
        soc, prev_soc = _finite(row.get("soc")), _finite(prev.get("soc"))
        if soc is None or prev_soc is None or _is_outage(row, threshold_w):
            back_at = None
        elif prev_soc >= 100 and soc == 99:  # drop: one step lost since the previous top-up ended
            wh_now = _finite(row.get("bat_wh"))
            if back_at is not None and wh_now is not None and back_wh is not None and back_wh > wh_now:
                span = row["ts"] - back_at
                if 0 < span <= STANDBY_MAX_INTERVAL_SECONDS:
                    estimates.append((back_wh - wh_now) / (span / 3600.0))
            back_at = None
        elif prev_soc == 99 and soc >= 100:  # top-up finished
            back_at, back_wh = row["ts"], _finite(row.get("bat_wh"))
        elif soc < 99:
            back_at = None
    if not estimates:
        return None
    estimates.sort()
    mid = len(estimates) // 2
    median = estimates[mid] if len(estimates) % 2 else (estimates[mid - 1] + estimates[mid]) / 2.0
    return round(median)


def compute_insights(rows, *, now, current_offgrid_w, temperature_c, threshold_w=BACKUP_DRAW_THRESHOLD_W,
                     chart_events=None, confirm_gap=gridstate.DEFAULT_CONFIRM_GAP_SECONDS, load_stats=None,
                     bypass="auto", bypass_seen=False):
    """`rows`: the last 24 h. `load_stats`: (mean W, covered s) of the 7-day backup draw (see backup_load_stats)."""
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
        "today": today_durations(rows, now=now, threshold_w=threshold_w, confirm_gap=confirm_gap, bypass=bypass,
                                 bypass_seen=bypass_seen),
        "outage_periods": outage_periods(rows, now=now, threshold_w=threshold_w, confirm_gap=confirm_gap,
                                         bypass=bypass, bypass_seen=bypass_seen),
        "chart_events": list(chart_events or []),
        "standby_w": standby_consumption(rows, now=now, threshold_w=threshold_w),
        "last_grid_charge": last_grid_charge(rows, now=now, threshold_w=threshold_w),
        "backup_load_mean_w_7d": (load_stats or (None, 0))[0],
        "backup_load_coverage_seconds_7d": (load_stats or (None, 0))[1],
    }

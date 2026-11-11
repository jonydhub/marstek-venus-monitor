#!/usr/bin/env python3
"""Read-only local dashboard for Marstek VenusE 3.0. No control methods are exposed."""
from __future__ import annotations

import hmac
import json
import logging
import math
import os
import re
import socket
import sys
import threading
import time
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

from config import ConfigError, load_env_file, load_settings, validate
import events as journal
from history import HistoryStore, compute_insights, measured_backup_load
from outage import build_outage_service, detect_grid_state
from netcache import CachedJsonClient
from regions import build_region_catalog
from uaoutages import NOW_URL

ROOT = Path(__file__).resolve().parent
KYIV_TZ = ZoneInfo("Europe/Kyiv")
# Optional .env-style file; real environment variables always win.
load_env_file(os.environ.get("MARSTEK_CONFIG") or (ROOT / ".env"), os.environ)
try:
    SETTINGS = load_settings(os.environ, default_history_db=str(ROOT / "data" / "history.db"))
except ConfigError as exc:
    sys.exit(f"Configuration error: {exc}")

HOST = SETTINGS.host
PORT = SETTINGS.port
HTTP_HOST = SETTINGS.bind
HTTP_PORT = SETTINGS.http_port
API_TOKEN = SETTINGS.api_token
POLL_SECONDS = 30
MIN_POLL_SECONDS = 15  # VenusE drops requests <1 s apart; a poll is 6 paced requests
BATTERY_RATE_WINDOW_SECONDS = 300
BATTERY_RATE_MIN_SECONDS = 120
BATTERY_RATE_DEADBAND_W = 10.0
BATTERY_TREND_WINDOW_SECONDS = 1800  # SOC-step trend: a 1 % step (≈ 51 Wh) takes ~10 min at 300 W
MEASURED_MIN_W = 10.0                # |ongrid|/|offgrid| below this counts as 0 (no measured flow)
METHODS = ("Wifi.GetStatus", "BLE.GetStatus", "Bat.GetStatus", "ES.GetStatus", "ES.GetMode", "EM.GetStatus")
# Read-only by construction: only `Component.GetSomething` queries may ever leave this process.
_READ_ONLY_METHOD = re.compile(r"[A-Za-z]+\.Get[A-Za-z]+")
MAX_REQUEST_ID = 65535  # the device echoes ids as uint16


def is_read_only_method(method) -> bool:
    return isinstance(method, str) and _READ_ONLY_METHOD.fullmatch(method) is not None
HISTORY_DB = Path(SETTINGS.history_db)
HISTORY_WINDOW_SECONDS = 24 * 3600
EXPECTED_LOAD_W = SETTINGS.expected_load_w
BACKUP_THRESHOLD_W = SETTINGS.backup_threshold_w
UA_OUTAGES_CLIENT = CachedJsonClient(ttl=600)


def _optional_float(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def _signed_ac_battery_power(ongrid_w, offgrid_w):
    """Battery power from the measured AC ports (+ = charging, − = discharging), or None if both idle.

    Sign convention of ES.GetStatus on a Venus E (verified on fw 148, Manual mode): ``ongrid_power`` < 0 means
    power flows grid → battery (charging; a 99→100 % top-up reads ≈ −1195 W), > 0 means the battery outputs
    to the grid-side port (load/export). ``offgrid_power`` is the backup-output draw, always battery output.
    """
    ongrid, offgrid = _optional_float(ongrid_w), _optional_float(offgrid_w)
    ongrid = ongrid if ongrid is not None and abs(ongrid) >= MEASURED_MIN_W else 0.0
    offgrid = abs(offgrid) if offgrid is not None and abs(offgrid) >= MEASURED_MIN_W else 0.0
    if not ongrid and not offgrid:
        return None
    return -ongrid - offgrid


def _quantized_steps(clean, rated):
    """Capacity changes [(t, ΔWh)] when bat_capacity moves only in whole SOC steps (rated/100), else None."""
    try:
        quantum = float(rated) / 100.0
    except (TypeError, ValueError):
        return None
    if not math.isfinite(quantum) or quantum <= 0:
        return None
    changes = [(b[0], b[1] - a[1]) for a, b in zip(clean, clean[1:]) if abs(b[1] - a[1]) > 0.5]
    if not changes:
        return None
    for _, delta in changes:
        steps = abs(delta) / quantum
        if round(steps) < 1 or abs(steps - round(steps)) > 0.15:
            return None
    return changes


def calculate_battery_flow(samples: list[tuple[float, float]], battery_capacity_wh, rated_capacity_wh,
                           ongrid_w=None, offgrid_w=None) -> dict:
    """Battery power and ETA: measured AC power first, the stored-Wh trend only as a marked estimate.

    1. ``ongrid_power`` / ``offgrid_power`` ≠ 0 → measured (``estimated`` = False), see _signed_ac_battery_power.
    2. Both 0 → estimate from bat_capacity. A Venus E reports bat_capacity in whole SOC steps (rated/100 ≈ 51 Wh),
       so one step (e.g. the 100→99→100 % standby top-up) is not a trend: a quantized series needs ≥ 2 steps in
       the same direction within BATTERY_TREND_WINDOW_SECONDS, otherwise it is idle (reason «soc_step_guard»).
       A smooth (non-quantized) series uses the 5-min linear regression.
    """
    result = {
        "available": False,
        "power_w": None,
        "direction": "unknown",
        "window_seconds": 0,
        "sample_count": 0,
        "method": "battery_capacity_delta_regression",
        "estimated": True,
        "reason": "collecting_samples",
        "charge_eta_seconds": None,
        "discharge_eta_seconds": None,
    }
    clean = []
    for timestamp, energy_wh in samples:
        try:
            timestamp, energy_wh = float(timestamp), float(energy_wh)
        except (TypeError, ValueError):
            continue
        if math.isfinite(timestamp) and math.isfinite(energy_wh):
            clean.append((timestamp, energy_wh))

    measured = _signed_ac_battery_power(ongrid_w, offgrid_w)
    power_w = None
    if measured is not None:
        power_w = measured
        result.update({"method": "measured_ac_power", "estimated": False, "sample_count": len(clean)})
    else:
        if not clean:
            return result
        newest = clean[-1][0]
        trend = [(t, energy) for t, energy in clean if 0 <= newest - t <= BATTERY_TREND_WINDOW_SECONDS]
        recent = [(t, energy) for t, energy in trend if newest - t <= BATTERY_RATE_WINDOW_SECONDS]
        result["sample_count"] = len(recent)
        steps = _quantized_steps(trend, rated_capacity_wh)
        if steps is not None:
            result.update({"method": "battery_capacity_soc_steps", "sample_count": len(trend),
                           "window_seconds": round(newest - trend[0][0])})
            same_sign = all(d > 0 for _, d in steps) or all(d < 0 for _, d in steps)
            power_w = 0.0
            if len(steps) >= 2 and same_sign:
                span = steps[-1][0] - steps[0][0]
                interval = span / (len(steps) - 1)
                fresh = newest - steps[-1][0] <= max(2.0 * interval, BATTERY_RATE_WINDOW_SECONDS)
                if span > 0 and fresh:
                    power_w = sum(d for _, d in steps[1:]) / span * 3600.0
            if power_w == 0.0:
                result["reason"] = "soc_step_guard"
        else:
            if len(recent) < 2:
                return result
            duration = recent[-1][0] - recent[0][0]
            result["window_seconds"] = round(duration)
            if duration < BATTERY_RATE_MIN_SECONDS:
                return result
            origin = recent[0][0]
            xs = [t - origin for t, _ in recent]
            ys = [energy for _, energy in recent]
            mean_x = sum(xs) / len(xs)
            mean_y = sum(ys) / len(ys)
            variance = sum((x - mean_x) ** 2 for x in xs)
            if variance <= 0:
                result["reason"] = "insufficient_time_span"
                return result
            slope_wh_per_second = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / variance
            power_w = slope_wh_per_second * 3600.0
    if not math.isfinite(power_w):
        result["reason"] = "invalid_rate"
        return result

    if power_w >= BATTERY_RATE_DEADBAND_W:
        direction = "charging"
    elif power_w <= -BATTERY_RATE_DEADBAND_W:
        direction = "discharging"
    else:
        direction = "idle"
        power_w = 0.0

    result.update({
        "available": True,
        "power_w": round(power_w, 1),
        "direction": direction,
        "reason": result["reason"] if result["reason"] == "soc_step_guard" else None,
    })
    try:
        capacity = float(battery_capacity_wh)
        rated = float(rated_capacity_wh)
    except (TypeError, ValueError):
        capacity = rated = math.nan
    if math.isfinite(capacity) and math.isfinite(rated) and rated > 0:
        if direction == "charging":
            remaining_wh = max(0.0, rated - capacity)
            result["charge_eta_seconds"] = 0.0 if remaining_wh <= 0.5 else remaining_wh / power_w * 3600.0
        elif direction == "discharging":
            available_wh = max(0.0, capacity)
            result["discharge_eta_seconds"] = 0.0 if available_wh <= 0.5 else available_wh / abs(power_w) * 3600.0
    return result


class MarstekPoller:
    def __init__(self):
        self.lock = threading.Lock()
        self.data = {
            "device": {"model": "VenusE 3.0", "ip": HOST, "udp_port": PORT},
            "collected_at": None,
            "poll_interval_seconds": POLL_SECONDS,
            "methods": {},
            "battery_flow": calculate_battery_flow([], None, None),
        }
        self.seq = 100
        self.battery_samples = deque(maxlen=128)
        self.outage = build_outage_service(SETTINGS, ua_client=UA_OUTAGES_CLIENT)
        self.history = None  # HistoryStore, attached by __main__ so imports/tests never touch the real DB
        self.detector = None  # events.DetectorState (journal); in memory, rebuilt after a restart
        self.initial_journaled = False  # «Стан на старті» written once per process, at the first answered poll

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("0.0.0.0", 0))
        self.sock.settimeout(3.0)
        self.stop_event = threading.Event()
        self.wake_event = threading.Event()

    def next_request_id(self) -> int:
        self.seq = self.seq % MAX_REQUEST_ID + 1
        return self.seq

    def query(self, method: str) -> dict:
        if not is_read_only_method(method):
            raise ValueError(f"refusing non read-only method {method!r}")
        request_id = self.next_request_id()
        payload = json.dumps({"id": request_id, "method": method, "params": {"id": 0}}, separators=(",", ":")).encode()
        for attempt in range(2):
            self.sock.settimeout(2.0)
            self.sock.sendto(payload, (HOST, PORT))
            try:
                while True:
                    raw, peer = self.sock.recvfrom(8192)
                    if peer[0] != HOST:
                        continue
                    response = json.loads(raw.decode("utf-8"))
                    if response.get("id") != request_id:
                        continue
                    if response.get("error"):
                        err = response["error"]
                        return {"ok": False, "error": err.get("message", "Device error"), "code": err.get("code"), "data": err.get("data"), "src": response.get("src")}
                    return {"ok": True, "data": response.get("result", {}), "src": response.get("src")}
            except TimeoutError:
                if attempt == 0:
                    time.sleep(0.25)
        return {"ok": False, "error": "No response after retry"}

    def battery_flow(self, battery_status: dict, energy_status: dict | None = None) -> dict:
        es = ((energy_status or {}).get("data") or {}) if (energy_status or {}).get("ok") else {}
        if not battery_status.get("ok"):
            return {**calculate_battery_flow([], None, None), "reason": "battery_status_unavailable"}
        battery = battery_status.get("data") or {}
        try:
            capacity = float(battery["bat_capacity"])
            rated = float(battery["rated_capacity"])
        except (KeyError, TypeError, ValueError):
            return {**calculate_battery_flow([], None, None), "reason": "capacity_unavailable"}
        if not math.isfinite(capacity) or not math.isfinite(rated) or rated <= 0:
            return {**calculate_battery_flow([], None, None), "reason": "capacity_unavailable"}

        now = time.monotonic()
        self.battery_samples.append((now, capacity))
        while len(self.battery_samples) > 1 and now - self.battery_samples[1][0] > BATTERY_TREND_WINDOW_SECONDS:
            self.battery_samples.popleft()
        return calculate_battery_flow(list(self.battery_samples), capacity, rated,
                                      ongrid_w=es.get("ongrid_power"), offgrid_w=es.get("offgrid_power"))

    def record_history(self, results: dict, battery_flow: dict, now: float) -> list:
        """Store this poll's sample and return the last 24 h of samples (empty without a store)."""
        if self.history is None:
            return []
        battery = results.get("Bat.GetStatus", {})
        energy = results.get("ES.GetStatus", {})
        bat = (battery.get("data") or {}) if battery.get("ok") else {}
        es = (energy.get("data") or {}) if energy.get("ok") else {}
        if not bat and not es:
            return self._history_rows(now)
        soc = _optional_float(bat.get("soc", es.get("bat_soc")))
        sample = {
            "ts": now,
            "soc": None if soc is None else int(round(soc)),
            "bat_wh": _optional_float(bat.get("bat_capacity")),
            "flow_w": _optional_float(battery_flow.get("power_w")) if battery_flow.get("available") else None,
            "flow_dir": battery_flow.get("direction"),
            "offgrid_w": _optional_float(es.get("offgrid_power")),
            "ongrid_w": _optional_float(es.get("ongrid_power")),
            "temp_c": _optional_float(bat.get("bat_temp")),
        }
        try:
            self.history.record(sample)
        except Exception as exc:  # history must never break live telemetry
            logging.warning("history write failed: %s", exc)
        return self._history_rows(now)

    def _history_rows(self, now):
        try:
            return self.history.since(now - HISTORY_WINDOW_SECONDS)
        except Exception as exc:
            logging.warning("history read failed: %s", exc)
            return []

    def power_outage(self, results: dict, battery_flow: dict, measured_load_w=None) -> dict:
        energy = results.get("ES.GetStatus", {})
        battery = results.get("Bat.GetStatus", {})
        energy_data = (energy.get("data") or {}) if energy.get("ok") else {}
        bat = (battery.get("data") or {}) if battery.get("ok") else {}
        # Light state comes ONLY from the backup-output power; no answer from the battery = unknown.
        grid_state = detect_grid_state(
            energy_data.get("offgrid_power"), energy_data.get("ongrid_power"), bool(energy.get("ok")),
            threshold_w=BACKUP_THRESHOLD_W,
        )
        return self.outage.status(grid_state=grid_state, stored_wh=bat.get("bat_capacity"), battery_flow=battery_flow,
                                  expected_load_w=EXPECTED_LOAD_W, measured_load_w=measured_load_w,
                                  backup_output_w=energy_data.get("offgrid_power"),
                                  rated_wh=bat.get("rated_capacity") or energy_data.get("bat_cap"),
                                  soc=bat.get("soc", energy_data.get("bat_soc")),
                                  floor_pct=SETTINGS.discharge_floor_pct, backup_threshold_w=BACKUP_THRESHOLD_W)

    def journal(self, results: dict, battery_flow: dict, grid_state: str, now: float) -> list:
        """Feed this poll into the event detector and persist new events (never breaks telemetry)."""
        bat_r, es_r = results.get("Bat.GetStatus", {}), results.get("ES.GetStatus", {})
        bat = (bat_r.get("data") or {}) if bat_r.get("ok") else {}
        es = (es_r.get("data") or {}) if es_r.get("ok") else {}
        soc = _optional_float(bat.get("soc", es.get("bat_soc")))
        obs = journal.Observation(
            ok=bool(bat_r.get("ok") or es_r.get("ok")), grid_state=grid_state,
            direction=battery_flow.get("direction") or "unknown",
            battery_w=_optional_float(battery_flow.get("power_w")) if battery_flow.get("available") else None,
            battery_measured=battery_flow.get("estimated") is False,
            backup_w=_optional_float(es.get("offgrid_power")), soc=None if soc is None else int(round(soc)),
            temp_c=_optional_float(bat.get("bat_temp")))
        new_events, self.detector = journal.step(self.detector, obs, now, SETTINGS.anomaly_discharge_w)
        if obs.ok and not self.initial_journaled:
            new_events = [journal.initial_state_event(obs, now)] + new_events
            self.initial_journaled = True
        if self.history is not None:
            for event in new_events:
                try:
                    self.history.record_event(event)
                except Exception as exc:
                    logging.warning("event write failed: %s", exc)
        return new_events

    def chart_events(self, now: float) -> list:
        if self.history is None:
            return []
        try:
            rows = self.history.events(limit=200, since=now - HISTORY_WINDOW_SECONDS)
        except Exception as exc:
            logging.warning("event read failed: %s", exc)
            return []
        wanted = ("light_lost", "light_returned", "charging_started", "charged_full")
        return [{"t": event_time(e["ts"]), "kind": e["kind"]} for e in reversed(rows) if e["kind"] in wanted]

    def poll_once(self):
        results = {}
        for method in METHODS:
            try:
                results[method] = self.query(method)
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                results[method] = {"ok": False, "error": "No response" if isinstance(exc, TimeoutError) else str(exc)}
            # Keep one source socket and pace requests; VenusE drops UDP bursts.
            time.sleep(1.0)
        battery_flow = self.battery_flow(results.get("Bat.GetStatus", {}), results.get("ES.GetStatus", {}))
        now = time.time()
        rows = self.record_history(results, battery_flow, now)
        power_outage = self.power_outage(results, battery_flow, measured_backup_load(rows, now=now))
        es = (results.get("ES.GetStatus", {}).get("data") or {}) if results.get("ES.GetStatus", {}).get("ok") else {}
        bat = (results.get("Bat.GetStatus", {}).get("data") or {}) if results.get("Bat.GetStatus", {}).get("ok") else {}
        self.journal(results, battery_flow, power_outage.get("grid_state", "unknown"), now)
        insights = compute_insights(rows, now=now, current_offgrid_w=es.get("offgrid_power"),
                                    temperature_c=bat.get("bat_temp"), threshold_w=BACKUP_THRESHOLD_W,
                                    chart_events=self.chart_events(now))
        detector = self.detector
        connection = {"ok": bool(bat or es), "consecutive_failures": detector.failures if detector else 0,
                      "lost": bool(detector and detector.connection_lost)}
        with self.lock:
            self.data = {
                "device": {"model": "VenusE 3.0", "ip": HOST, "udp_port": PORT},
                "collected_at": datetime.now(timezone.utc).isoformat(),
                "poll_interval_seconds": POLL_SECONDS,
                "methods": results,
                "battery_flow": battery_flow,
                "power_outage": power_outage,
                "insights": insights,
                "connection": connection,
            }

    def wait(self, seconds) -> None:
        """Sleep until the next poll; apply_settings()/stop() wake it early."""
        self.wake_event.wait(seconds)
        self.wake_event.clear()

    def run(self):
        while not self.stop_event.is_set():
            if HOST:  # nothing to poll until the battery address is configured
                self.poll_once()
            if not self.stop_event.is_set():
                self.wait(POLL_SECONDS)

    def stop(self):
        self.stop_event.set()
        self.wake_event.set()

    def run_outage_refresh(self):
        while not self.stop_event.is_set():
            self.outage.refresh_if_due()
            self.stop_event.wait(30)

    def health(self):
        with self.lock:
            collected = self.data.get("collected_at")
            methods = self.data.get("methods") or {}
        age = None
        if collected:
            age = max(0, round((datetime.now(timezone.utc) - datetime.fromisoformat(collected)).total_seconds()))
        return {"ok": True, "device_responding": bool(methods.get("ES.GetStatus", {}).get("ok")),
                "last_poll_age_seconds": age}

    def snapshot(self):
        with self.lock:
            return json.loads(json.dumps(self.data))

def event_time(ts) -> str:
    return datetime.fromtimestamp(round(float(ts)), timezone.utc).astimezone(KYIV_TZ).isoformat(timespec="seconds")


def events_payload(query: str) -> dict:
    params = parse_qs(query or "")
    try:
        limit = int((params.get("limit") or ["200"])[0])
    except ValueError:
        limit = 200
    category = (params.get("category") or [None])[0]
    if category not in (None, "light", "battery", "connection", "anomaly", "system"):
        category = None
    rows = poller.history.events(limit=limit, category=category) if poller.history is not None else []
    first = poller.history.first_event_ts() if poller.history is not None else None
    return {"events": [{**row, "ts": event_time(row["ts"])} for row in rows],
            "journal_started_at": event_time(first) if first is not None else None}


def regions_catalog():
    """Canonical regions; live queue lists from ua-outages when reachable, else the static table."""
    try:
        ua_now = UA_OUTAGES_CLIENT.get(NOW_URL)
    except Exception as exc:
        logging.warning("ua-outages catalog unavailable: %s", exc)
        ua_now = None
    return build_region_catalog(ua_now)


class RateLimiter:
    """Sliding-window request counter per client IP."""

    def __init__(self, limit=120, window=60.0, clock=time.monotonic):
        self.limit, self.window, self.clock = int(limit), float(window), clock
        self.lock = threading.Lock()
        self.hits = {}

    def allow(self, key):
        now = self.clock()
        with self.lock:
            if len(self.hits) > 4096:  # drop idle clients
                self.hits = {k: q for k, q in self.hits.items() if q and now - q[-1] < self.window}
            queue = self.hits.setdefault(key, deque())
            while queue and now - queue[0] >= self.window:
                queue.popleft()
            if len(queue) >= self.limit:
                return False, max(1, int(self.window - (now - queue[0])) + 1)
            queue.append(now)
            return True, 0


RATE_LIMITER = RateLimiter(limit=SETTINGS.rate_limit_per_minute, window=60)


def token_ok(header_value, expected):
    if not expected:
        return True
    scheme, _, supplied = (header_value or "").partition(" ")
    if scheme.lower() != "bearer":
        return False
    return hmac.compare_digest(supplied.strip().encode(), expected.encode())


poller = MarstekPoller()


def apply_settings(settings, poll_seconds=None):
    """Swap configuration at runtime (used by the embedded Windows app; the CLI server never calls it)."""
    global SETTINGS, HOST, PORT, HTTP_HOST, HTTP_PORT, API_TOKEN, EXPECTED_LOAD_W, BACKUP_THRESHOLD_W, POLL_SECONDS
    changed_device = (settings.host, settings.port) != (HOST, PORT)
    SETTINGS = settings
    HOST, PORT = settings.host, settings.port
    HTTP_HOST, HTTP_PORT, API_TOKEN = settings.bind, settings.http_port, settings.api_token
    EXPECTED_LOAD_W, BACKUP_THRESHOLD_W = settings.expected_load_w, settings.backup_threshold_w
    if poll_seconds is not None:
        POLL_SECONDS = max(MIN_POLL_SECONDS, int(poll_seconds))
    poller.outage = build_outage_service(settings, ua_client=UA_OUTAGES_CLIENT)
    with poller.lock:
        if changed_device:
            poller.battery_samples.clear()
            poller.data = {**poller.data, "methods": {}, "collected_at": None,
                           "battery_flow": calculate_battery_flow([], None, None)}
        poller.data = {**poller.data, "device": {"model": "VenusE 3.0", "ip": HOST, "udp_port": PORT},
                       "poll_interval_seconds": POLL_SECONDS}
    poller.wake_event.set()


class Handler(BaseHTTPRequestHandler):
    server_version = "MarstekVenusMonitor/1.0"
    sys_version = ""

    def auth_exempt(self) -> bool:
        """Hook for embedders (e.g. a loopback-only desktop window); the server itself never exempts."""
        return False

    def log_message(self, fmt, *args):
        logging.info("http %s - %s", self.address_string(), fmt % args)

    def send_bytes(self, status: int, body: bytes, mime: str, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, status, payload, headers=None):
        body = json.dumps(payload, ensure_ascii=False).encode()
        return self.send_bytes(status, body, "application/json; charset=utf-8", headers)

    def handle_api(self, path, query=""):
        allowed, retry_after = RATE_LIMITER.allow(self.client_address[0])
        if not allowed:
            return self.send_json(429, {"error": "rate_limited"}, {"Retry-After": str(retry_after)})
        if not self.auth_exempt() and not token_ok(self.headers.get("Authorization"), API_TOKEN):
            return self.send_json(401, {"error": "unauthorized"}, {"WWW-Authenticate": 'Bearer realm="marstek"'})
        if path == "/api/status":
            return self.send_json(200, poller.snapshot())
        if path == "/api/events":
            try:
                return self.send_json(200, events_payload(query))
            except Exception as exc:
                logging.warning("events read failed: %s", exc)
                return self.send_json(500, {"error": "events_unavailable"})
        if path == "/api/regions":
            try:
                regions = regions_catalog()
            except Exception as exc:
                logging.warning("regions catalog failed: %s", exc)
                return self.send_json(502, {"error": "upstream_unavailable"})
            return self.send_json(200, {"source": "official oblenergo sources + ua-outages", "regions": regions})
        return self.send_json(404, {"error": "not_found"})

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith("/api/"):
            return self.handle_api(path, parsed.query)
        if path == "/health":
            return self.send_json(200, poller.health())
        files = {
            "/": ("index.html", "text/html; charset=utf-8"),
            "/app.js": ("app.js", "text/javascript; charset=utf-8"),
            "/style.css": ("style.css", "text/css; charset=utf-8"),
            "/favicon.svg": ("favicon.svg", "image/svg+xml"),
            "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json; charset=utf-8"),
            "/sw.js": ("sw.js", "application/javascript; charset=utf-8"),
            "/icons/marstek-192.png": ("icons/marstek-192.png", "image/png"),
            "/icons/marstek-512.png": ("icons/marstek-512.png", "image/png"),
            "/icons/marstek-maskable-512.png": ("icons/marstek-maskable-512.png", "image/png"),
        }
        item = files.get(path)
        if not item:
            return self.send_bytes(404, b"Not found", "text/plain; charset=utf-8")
        name, mime = item
        try:
            body = (ROOT / name).read_bytes()
        except FileNotFoundError:
            return self.send_bytes(404, b"Not found", "text/plain; charset=utf-8")
        return self.send_bytes(200, body, mime)

    def do_POST(self):
        self.send_bytes(405, b"Read-only dashboard", "text/plain; charset=utf-8")

    def do_PUT(self):
        self.send_bytes(405, b"Read-only dashboard", "text/plain; charset=utf-8")

    def do_DELETE(self):
        self.send_bytes(405, b"Read-only dashboard", "text/plain; charset=utf-8")

def parse_binds(bind: str, default_port: int) -> list[tuple[str, int]]:
    """MARSTEK_BIND: one or more comma-separated `host` or `host:port` entries (IPv6 as `[::1]:8767`)."""
    result: list[tuple[str, int]] = []
    for raw in (bind or "127.0.0.1").split(","):
        item = raw.strip()
        if not item:
            continue
        host, port = item, default_port
        if item.startswith("["):
            end = item.find("]")
            host = item[1:end]
            rest = item[end + 1:]
            if rest.startswith(":"):
                port = int(rest[1:])
        elif item.count(":") == 1:
            host, port_text = item.split(":")
            port = int(port_text)
        if not 0 < port < 65536:
            raise ValueError(f"bad port in MARSTEK_BIND entry {item!r}")
        if (host, port) not in result:
            result.append((host, port))
    return result or [("127.0.0.1", default_port)]


def main():
    errors = validate(SETTINGS)
    if errors:
        for error in errors:
            logging.error("config: %s", error)
        sys.exit("Configuration error, see messages above. Example: server/config.example.env")
    binds = parse_binds(HTTP_HOST, HTTP_PORT)
    for host, _ in binds:
        if host not in ("127.0.0.1", "::1", "localhost") and not API_TOKEN:
            logging.warning("Listening on %s without MARSTEK_API_TOKEN: anyone who can reach this port sees your data",
                            host)
    HISTORY_DB.parent.mkdir(parents=True, exist_ok=True)
    poller.history = HistoryStore(str(HISTORY_DB))
    poller.history.prune(time.time())
    try:
        poller.history.record_event(journal.server_started_event(time.time()))
    except Exception as exc:  # the journal must never stop the server
        logging.warning("event write failed: %s", exc)
    threading.Thread(target=poller.run_outage_refresh, name="outage", daemon=True).start()
    threading.Thread(target=poller.run, name="marstek-poller", daemon=True).start()
    servers = []
    for host, port in binds:
        srv = ThreadingHTTPServer((host, port), Handler)
        srv.daemon_threads = True
        servers.append(srv)
    logging.info("Marstek read-only monitor: %s device=%s:%s outage=%s auth=%s",
                 ", ".join(f"http://{h}:{p}" for h, p in binds), HOST, PORT, SETTINGS.outage_provider,
                 "token" if API_TOKEN else "none")
    for srv in servers[1:]:
        threading.Thread(target=srv.serve_forever, name=f"http-{srv.server_address[1]}", daemon=True).start()
    servers[0].serve_forever()

if __name__ == "__main__":
    main()

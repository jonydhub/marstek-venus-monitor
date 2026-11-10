#!/usr/bin/env python3
"""Read-only local dashboard for Marstek VenusE 3.0. No control methods are exposed."""
from __future__ import annotations

import hmac
import json
import logging
import math
import os
import socket
import sys
import threading
import time
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from config import ConfigError, load_env_file, load_settings, validate
from history import HistoryStore, compute_insights, measured_backup_load
from outage import build_outage_service, detect_grid_state
from netcache import CachedJsonClient
from regions import build_region_catalog
from svitlo import SvitloClient
from uaoutages import NOW_URL

ROOT = Path(__file__).resolve().parent
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
BATTERY_RATE_WINDOW_SECONDS = 300
BATTERY_RATE_MIN_SECONDS = 120
BATTERY_RATE_DEADBAND_W = 10.0
METHODS = ("Wifi.GetStatus", "BLE.GetStatus", "Bat.GetStatus", "ES.GetStatus", "ES.GetMode", "EM.GetStatus")
HISTORY_DB = Path(SETTINGS.history_db)
HISTORY_WINDOW_SECONDS = 24 * 3600
EXPECTED_LOAD_W = SETTINGS.expected_load_w
BACKUP_THRESHOLD_W = SETTINGS.backup_threshold_w
SVITLO_CLIENT = SvitloClient()
UA_OUTAGES_CLIENT = CachedJsonClient(ttl=600)


def _optional_float(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def calculate_battery_flow(samples: list[tuple[float, float]], battery_capacity_wh, rated_capacity_wh) -> dict:
    """Estimate battery power and ETA from the measured Wh slope, never from grid-power sign."""
    result = {
        "available": False,
        "power_w": None,
        "direction": "unknown",
        "window_seconds": 0,
        "sample_count": 0,
        "method": "battery_capacity_delta_regression",
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
    if not clean:
        return result

    newest = clean[-1][0]
    recent = [(t, energy) for t, energy in clean if 0 <= newest - t <= BATTERY_RATE_WINDOW_SECONDS]
    result["sample_count"] = len(recent)
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
        "reason": None,
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
        self.battery_samples = deque(maxlen=32)
        self.outage = build_outage_service(SETTINGS, svitlo_client=SVITLO_CLIENT, ua_client=UA_OUTAGES_CLIENT)
        self.history = None  # HistoryStore, attached by __main__ so imports/tests never touch the real DB

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("0.0.0.0", 0))
        self.sock.settimeout(3.0)
        self.stop_event = threading.Event()

    def query(self, method: str) -> dict:
        self.seq = (self.seq + 1) % 2_000_000_000
        request_id = self.seq
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

    def battery_flow(self, battery_status: dict) -> dict:
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
        while len(self.battery_samples) > 1 and now - self.battery_samples[1][0] > BATTERY_RATE_WINDOW_SECONDS:
            self.battery_samples.popleft()
        return calculate_battery_flow(list(self.battery_samples), capacity, rated)

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
        energy_data = energy.get("data") or {}
        grid_state = detect_grid_state(
            energy_data.get("offgrid_power"), energy_data.get("ongrid_power"), bool(energy.get("ok")),
            threshold_w=BACKUP_THRESHOLD_W,
        )
        stored = (battery.get("data") or {}).get("bat_capacity") if battery.get("ok") else None
        return self.outage.status(grid_state=grid_state, stored_wh=stored, battery_flow=battery_flow,
                                  expected_load_w=EXPECTED_LOAD_W, measured_load_w=measured_load_w)

    def poll_once(self):
        results = {}
        for method in METHODS:
            try:
                results[method] = self.query(method)
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                results[method] = {"ok": False, "error": "No response" if isinstance(exc, TimeoutError) else str(exc)}
            # Keep one source socket and pace requests; VenusE drops UDP bursts.
            time.sleep(1.0)
        battery_flow = self.battery_flow(results.get("Bat.GetStatus", {}))
        now = time.time()
        rows = self.record_history(results, battery_flow, now)
        power_outage = self.power_outage(results, battery_flow, measured_backup_load(rows, now=now))
        es = (results.get("ES.GetStatus", {}).get("data") or {}) if results.get("ES.GetStatus", {}).get("ok") else {}
        bat = (results.get("Bat.GetStatus", {}).get("data") or {}) if results.get("Bat.GetStatus", {}).get("ok") else {}
        insights = compute_insights(rows, now=now, current_offgrid_w=es.get("offgrid_power"),
                                    temperature_c=bat.get("bat_temp"))
        with self.lock:
            self.data = {
                "device": {"model": "VenusE 3.0", "ip": HOST, "udp_port": PORT},
                "collected_at": datetime.now(timezone.utc).isoformat(),
                "poll_interval_seconds": POLL_SECONDS,
                "methods": results,
                "battery_flow": battery_flow,
                "power_outage": power_outage,
                "insights": insights,
            }

    def run(self):
        while not self.stop_event.is_set():
            self.poll_once()
            self.stop_event.wait(POLL_SECONDS)

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

def regions_catalog():
    """Canonical regions with queues from ua-outages + svitlo.live; one upstream may be down."""
    ua_now = svitlo_catalog = None
    errors = []
    try:
        ua_now = UA_OUTAGES_CLIENT.get(NOW_URL)
    except Exception as exc:
        errors.append(exc)
    try:
        svitlo_catalog = SVITLO_CLIENT.catalog()
    except Exception as exc:
        errors.append(exc)
    if len(errors) == 2:
        raise errors[0]
    return build_region_catalog(ua_now, svitlo_catalog)


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


class Handler(BaseHTTPRequestHandler):
    server_version = "MarstekVenusMonitor/1.0"
    sys_version = ""

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

    def handle_api(self, path):
        allowed, retry_after = RATE_LIMITER.allow(self.client_address[0])
        if not allowed:
            return self.send_json(429, {"error": "rate_limited"}, {"Retry-After": str(retry_after)})
        if not token_ok(self.headers.get("Authorization"), API_TOKEN):
            return self.send_json(401, {"error": "unauthorized"}, {"WWW-Authenticate": 'Bearer realm="marstek"'})
        if path == "/api/status":
            return self.send_json(200, poller.snapshot())
        if path == "/api/regions":
            try:
                regions = regions_catalog()
            except Exception as exc:
                logging.warning("regions catalog failed: %s", exc)
                return self.send_json(502, {"error": "upstream_unavailable"})
            return self.send_json(200, {"source": "ua-outages + svitlo.live", "regions": regions})
        return self.send_json(404, {"error": "not_found"})

    def do_GET(self):
        path = urlparse(self.path).path
        if path.startswith("/api/"):
            return self.handle_api(path)
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

def main():
    errors = validate(SETTINGS)
    if errors:
        for error in errors:
            logging.error("config: %s", error)
        sys.exit("Configuration error, see messages above. Example: server/config.example.env")
    if HTTP_HOST not in ("127.0.0.1", "::1", "localhost") and not API_TOKEN:
        logging.warning("Listening on %s without MARSTEK_API_TOKEN: anyone who can reach this port sees your data",
                        HTTP_HOST)
    HISTORY_DB.parent.mkdir(parents=True, exist_ok=True)
    poller.history = HistoryStore(str(HISTORY_DB))
    poller.history.prune(time.time())
    threading.Thread(target=poller.run_outage_refresh, name="outage", daemon=True).start()
    threading.Thread(target=poller.run, name="marstek-poller", daemon=True).start()
    server = ThreadingHTTPServer((HTTP_HOST, HTTP_PORT), Handler)
    server.daemon_threads = True
    logging.info("Marstek read-only monitor: http://%s:%s device=%s:%s outage=%s auth=%s",
                 HTTP_HOST, HTTP_PORT, HOST, PORT, SETTINGS.outage_provider, "token" if API_TOKEN else "none")
    server.serve_forever()


if __name__ == "__main__":
    main()

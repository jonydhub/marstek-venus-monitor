"""JSON config in %APPDATA%\\MarstekVenusMonitor\\config.json: load, normalise, migrate, save, map to server Settings."""
from __future__ import annotations

import copy
import ipaddress
import json
import logging
import math
import os
import secrets
from pathlib import Path

from mvm.alerts import ALERT_IDS

SCHEMA_VERSION = 2
DEFAULT_PORT = 30000
DEFAULT_DASHBOARD_PORT = 8767
DEFAULT_POLL_SECONDS = 30  # a home dashboard may already poll the same battery; stay gentle
MIN_POLL_SECONDS = 15
MAX_POLL_SECONDS = 3600
log = logging.getLogger(__name__)


def default_config() -> dict:
    return {
        "schema": SCHEMA_VERSION,
        "battery": {"host": "", "port": DEFAULT_PORT},
        "poll_seconds": DEFAULT_POLL_SECONDS,
        "dashboard_port": DEFAULT_DASHBOARD_PORT,
        "outage": {"region": "", "queue": ""},
        "backup_threshold_w": 5.0,
        "expected_load_w": None,
        "phone_access": {"enabled": False, "token": ""},
        "alerts": {alert_id: True for alert_id in ALERT_IDS},
        "native_window": True,
    }


def _int(value, default, lo, hi):
    try:
        number = int(float(value))
    except (TypeError, ValueError):
        return default
    return number if lo <= number <= hi else default


def _float(value, default, lo=0.0, hi=1e6):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) and lo <= number <= hi else default


def _text(value, limit=200):
    return str(value or "").strip()[:limit]


def migrate(raw: dict) -> dict:
    """Bring older layouts to the current schema. v1 was flat: host/port/region/queue/threshold_w/lan/token."""
    raw = dict(raw or {})
    schema = raw.get("schema") if isinstance(raw.get("schema"), int) else 1
    if schema >= SCHEMA_VERSION:
        return raw
    cfg = default_config()
    cfg["battery"] = {"host": raw.get("host", ""), "port": raw.get("port", DEFAULT_PORT)}
    cfg["outage"] = {"region": raw.get("region", ""), "queue": raw.get("queue", raw.get("group", ""))}
    for old, new in (("threshold_w", "backup_threshold_w"), ("backup_threshold_w", "backup_threshold_w"),
                     ("expected_load_w", "expected_load_w"), ("poll_seconds", "poll_seconds"),
                     ("dashboard_port", "dashboard_port")):
        if old in raw:
            cfg[new] = raw[old]
    cfg["phone_access"] = {"enabled": bool(raw.get("lan", False)), "token": raw.get("token", "")}
    if isinstance(raw.get("alerts"), dict):
        cfg["alerts"].update(raw["alerts"])
    return cfg


def normalise(raw: dict) -> dict:
    cfg = default_config()
    raw = raw if isinstance(raw, dict) else {}
    cfg["schema"] = max(SCHEMA_VERSION, raw.get("schema", SCHEMA_VERSION) if isinstance(raw.get("schema"), int)
                        else SCHEMA_VERSION)
    battery = raw.get("battery") if isinstance(raw.get("battery"), dict) else {}
    host = _text(battery.get("host"), 64)
    cfg["battery"] = {"host": host if valid_ipv4(host) else "", "port": _int(battery.get("port"), DEFAULT_PORT, 1, 65535)}
    poll = _int(raw.get("poll_seconds"), DEFAULT_POLL_SECONDS, 1, 10**6)
    cfg["poll_seconds"] = min(MAX_POLL_SECONDS, max(MIN_POLL_SECONDS, poll))
    cfg["dashboard_port"] = _int(raw.get("dashboard_port"), DEFAULT_DASHBOARD_PORT, 1024, 65535)
    outage = raw.get("outage") if isinstance(raw.get("outage"), dict) else {}
    cfg["outage"] = {"region": _text(outage.get("region"), 64).lower(), "queue": _text(outage.get("queue"), 16)}
    cfg["backup_threshold_w"] = _float(raw.get("backup_threshold_w"), 5.0, 0.0, 5000.0)
    expected = _float(raw.get("expected_load_w"), None, 0.0, 10000.0)
    cfg["expected_load_w"] = expected if expected else None
    pa = raw.get("phone_access") if isinstance(raw.get("phone_access"), dict) else {}
    cfg["phone_access"] = {"enabled": bool(pa.get("enabled", False)), "token": _text(pa.get("token"), 128)}
    alerts = raw.get("alerts") if isinstance(raw.get("alerts"), dict) else {}
    cfg["alerts"] = {alert_id: bool(alerts.get(alert_id, True)) for alert_id in ALERT_IDS}
    cfg["native_window"] = bool(raw.get("native_window", True))
    return cfg


def load_config(path) -> dict:
    path = Path(path)
    try:
        raw = json.loads(path.read_text("utf-8"))
    except FileNotFoundError:
        return default_config()
    except (OSError, ValueError) as exc:
        log.warning("config unreadable (%s), using defaults; kept as %s.bad", type(exc).__name__, path.name)
        try:
            os.replace(path, path.with_name(path.name + ".bad"))
        except OSError:
            pass
        return default_config()
    return normalise(migrate(raw))


def save_config(path, cfg: dict) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(normalise(cfg), ensure_ascii=False, indent=2), "utf-8")
    os.replace(tmp, path)


def valid_ipv4(text) -> bool:
    try:
        ipaddress.IPv4Address(str(text))
    except ValueError:
        return False
    return True


def is_configured(cfg: dict) -> bool:
    return bool(cfg["battery"]["host"])


def ensure_token(cfg: dict) -> dict:
    cfg = copy.deepcopy(cfg)
    if cfg["phone_access"]["enabled"] and len(cfg["phone_access"]["token"]) < 20:
        cfg["phone_access"]["token"] = secrets.token_urlsafe(24)
    return cfg


def to_settings(cfg: dict, history_db: str):
    """Map to the server's Settings (server/config.py) — the embedded server keeps one config model."""
    from config import load_settings
    region, queue = cfg["outage"]["region"], cfg["outage"]["queue"]
    lan = cfg["phone_access"]["enabled"]
    env = {
        "MARSTEK_HOST": cfg["battery"]["host"],
        "MARSTEK_PORT": str(cfg["battery"]["port"]),
        "MARSTEK_BIND": "0.0.0.0" if lan else "127.0.0.1",
        "MARSTEK_DASHBOARD_PORT": str(cfg["dashboard_port"]),
        "MARSTEK_API_TOKEN": cfg["phone_access"]["token"] if lan else "",
        "MARSTEK_OUTAGE_PROVIDER": "auto" if region and queue else "off",
        "MARSTEK_OUTAGE_REGION": region if region and queue else "",
        "MARSTEK_OUTAGE_QUEUE": queue if region and queue else "",
        "MARSTEK_BACKUP_THRESHOLD_W": str(cfg["backup_threshold_w"]),
        "MARSTEK_EXPECTED_LOAD_W": "" if cfg["expected_load_w"] is None else str(cfg["expected_load_w"]),
        "MARSTEK_HISTORY_DB": str(history_db),
    }
    return load_settings(env, default_history_db=str(history_db))


def apply_form(cfg: dict, form: dict):
    """Validate a settings form; return (new_config, errors). On errors the original config is returned."""
    errors = []
    new = copy.deepcopy(cfg)
    form = form if isinstance(form, dict) else {}
    if "host" in form:
        host = _text(form.get("host"), 64)
        if host and not valid_ipv4(host):
            errors.append("IP-адреса батареї має бути у форматі 192.168.0.50")
        new["battery"]["host"] = host
    if "port" in form:
        port = _int(form.get("port"), None, 1, 65535)
        if port is None:
            errors.append("Порт батареї: число від 1 до 65535 (зазвичай 30000)")
        else:
            new["battery"]["port"] = port
    if "region" in form or "queue" in form:
        region = _text(form.get("region"), 64).lower()
        queue = _text(form.get("queue"), 16)
        if region:
            from regions import resolve_region
            resolved = resolve_region(region)
            if resolved is None:
                errors.append("Невідомий регіон — виберіть зі списку")
            else:
                region = resolved
            if not queue:
                errors.append("Виберіть чергу (наприклад 3.1) або приберіть регіон")
        new["outage"] = {"region": region, "queue": queue if region else ""}
    for key, label, hi in (("backup_threshold_w", "Поріг резервного виходу", 5000.0),
                           ("expected_load_w", "Середнє споживання", 10000.0)):
        if key in form:
            raw = _text(form.get(key), 16).replace(",", ".")
            if raw == "":
                new[key] = None if key == "expected_load_w" else 5.0
                continue
            value = _float(raw, None, 0.0, hi)
            if value is None:
                errors.append(f"{label}: число від 0 до {int(hi)} Вт")
            else:
                new[key] = value if (value or key == "backup_threshold_w") else None
    if "poll_seconds" in form:
        poll = _int(form.get("poll_seconds"), None, MIN_POLL_SECONDS, MAX_POLL_SECONDS)
        if poll is None:
            errors.append(f"Інтервал опитування: {MIN_POLL_SECONDS}–{MAX_POLL_SECONDS} с (рекомендовано 30)")
        else:
            new["poll_seconds"] = poll
    if "native_window" in form:
        new["native_window"] = bool(form.get("native_window"))
    if isinstance(form.get("alerts"), dict):
        for alert_id, on in form["alerts"].items():
            if alert_id in new["alerts"]:
                new["alerts"][alert_id] = bool(on)
    if errors:
        return cfg, errors
    return normalise(new), []

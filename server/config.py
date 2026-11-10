"""Configuration from environment variables and an optional `.env`-style file.

No personal defaults: the battery address must be configured explicitly.
See config.example.env for every supported variable.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass
from pathlib import Path

PROVIDERS = ("auto", "off", "ua-outages", "svitlo", "loe", "yasno")
_LINE_RE = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$")


class ConfigError(ValueError):
    pass


def parse_env_file(text: str) -> dict:
    """Parse KEY=VALUE lines; `#` comments, blank lines and `export` prefixes are allowed."""
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = _LINE_RE.match(line)
        if not match:
            continue
        key, value = match.groups()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def load_env_file(path, environ) -> bool:
    """Merge a config file into `environ` without overriding variables already set."""
    try:
        text = Path(path).read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError, PermissionError):
        return False
    for key, value in parse_env_file(text).items():
        environ.setdefault(key, value)
    return True


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    bind: str
    http_port: int
    api_token: str
    rate_limit_per_minute: int
    outage_provider: str
    outage_region: str
    outage_queue: str
    loe_building: str
    loe_otg_id: int | None
    loe_city_id: int | None
    loe_street_id: int | None
    backup_threshold_w: float
    expected_load_w: float | None
    history_db: str


def _text(env, key, default=""):
    return str(env.get(key, default) or "").strip()


def _int(env, key, default=None):
    raw = _text(env, key)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        raise ConfigError(f"{key} must be an integer, got {raw!r}") from None


def _float(env, key, default=None):
    raw = _text(env, key)
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        raise ConfigError(f"{key} must be a number, got {raw!r}") from None
    if not math.isfinite(value):
        raise ConfigError(f"{key} must be finite")
    return value


def load_settings(env, default_history_db="data/history.db") -> Settings:
    expected = _float(env, "MARSTEK_EXPECTED_LOAD_W")
    return Settings(
        host=_text(env, "MARSTEK_HOST"),
        port=_int(env, "MARSTEK_PORT", 30000),
        bind=_text(env, "MARSTEK_BIND") or "127.0.0.1",
        http_port=_int(env, "MARSTEK_DASHBOARD_PORT", 8767),
        api_token=_text(env, "MARSTEK_API_TOKEN"),
        rate_limit_per_minute=_int(env, "MARSTEK_RATE_LIMIT_PER_MIN", 120),
        outage_provider=(_text(env, "MARSTEK_OUTAGE_PROVIDER").lower()
                         or ("auto" if _text(env, "MARSTEK_OUTAGE_REGION") else "off")),
        outage_region=_text(env, "MARSTEK_OUTAGE_REGION"),
        outage_queue=_text(env, "MARSTEK_OUTAGE_QUEUE") or _text(env, "MARSTEK_OUTAGE_GROUP"),
        loe_building=_text(env, "MARSTEK_OUTAGE_BUILDING"),
        loe_otg_id=_int(env, "MARSTEK_OUTAGE_OTG_ID"),
        loe_city_id=_int(env, "MARSTEK_OUTAGE_CITY_ID"),
        loe_street_id=_int(env, "MARSTEK_OUTAGE_STREET_ID"),
        backup_threshold_w=_float(env, "MARSTEK_BACKUP_THRESHOLD_W", 5.0),
        expected_load_w=expected if expected and expected > 0 else None,
        history_db=_text(env, "MARSTEK_HISTORY_DB") or default_history_db,
    )


def validate(settings: Settings) -> list[str]:
    """Return human-readable problems; empty list means the server can start."""
    errors = []
    if not settings.host:
        errors.append("MARSTEK_HOST is not set: put the battery's LAN IP address here "
                      "(see config.example.env), e.g. MARSTEK_HOST=192.168.0.50")
    if not 0 < settings.port < 65536:
        errors.append("MARSTEK_PORT must be 1..65535")
    if not 0 < settings.http_port < 65536:
        errors.append("MARSTEK_DASHBOARD_PORT must be 1..65535")
    if settings.rate_limit_per_minute < 1:
        errors.append("MARSTEK_RATE_LIMIT_PER_MIN must be >= 1")
    if settings.outage_provider not in PROVIDERS:
        errors.append(f"MARSTEK_OUTAGE_PROVIDER must be one of {', '.join(PROVIDERS)}")
    if settings.outage_provider in ("auto", "ua-outages", "svitlo", "yasno"):
        name = settings.outage_provider
        if not settings.outage_region:
            errors.append(f"MARSTEK_OUTAGE_REGION is required for provider {name} (see GET /api/regions)")
        if not settings.outage_queue:
            errors.append(f"MARSTEK_OUTAGE_QUEUE is required for provider {name}, e.g. 3.1")
    region_id = None
    if settings.outage_region and settings.outage_provider not in ("off", "loe"):
        from regions import resolve_region
        region_id = resolve_region(settings.outage_region)
        if region_id is None:
            errors.append(f"MARSTEK_OUTAGE_REGION={settings.outage_region!r} is unknown; "
                          "use an id from GET /api/regions, e.g. kyiv, lviv, odesa, kyiv-region")
    if settings.outage_provider == "yasno" and region_id:
        from yasno import YASNO_REGIONS
        if region_id not in YASNO_REGIONS:
            errors.append(f"provider yasno supports only regions {', '.join(sorted(YASNO_REGIONS))}; "
                          "use MARSTEK_OUTAGE_PROVIDER=auto or svitlo")
    if settings.outage_provider == "loe" and not settings.outage_queue:
        errors.append("MARSTEK_OUTAGE_QUEUE is required for provider loe, e.g. 1.1")
    return errors

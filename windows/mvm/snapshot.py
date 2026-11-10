"""Typed view of the server's /api/status snapshot + Ukrainian formatting helpers (shared by alerts and tray)."""
from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timezone

try:
    from zoneinfo import ZoneInfo
    KYIV = ZoneInfo("Europe/Kyiv")
except Exception:  # pragma: no cover - tzdata missing; fall back to local time
    KYIV = None


def _num(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def parse_time(value):
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=KYIV or timezone.utc)


@dataclass
class View:
    poll_ok: bool
    soc: int | None
    direction: str  # charging / discharging / idle / unknown
    grid: str | None  # on / off / None
    backup_w: float | None
    capacity_wh: float | None
    rated_wh: float | None
    temperature_c: float | None
    power_w: float | None
    charge_eta_s: float | None
    discharge_eta_s: float | None
    next_start: datetime | None
    next_end: datetime | None
    next_start_raw: str | None
    next_duration_s: float | None
    current_end: datetime | None
    runtime_s: float | None
    covers: bool | None
    load_w: float | None
    group: str | None
    schedule_published: bool | None
    avg_backup_w: float | None
    has_outage_info: bool


def view(status) -> View:
    status = status if isinstance(status, dict) else {}
    methods = status.get("methods") or {}

    def data(name):
        item = methods.get(name) or {}
        return (item.get("data") or {}) if item.get("ok") else None

    bat, es = data("Bat.GetStatus"), data("ES.GetStatus")
    poll_ok = bat is not None or es is not None
    bat, es = bat or {}, es or {}
    soc = _num(bat.get("soc", es.get("bat_soc")))
    flow = status.get("battery_flow") or {}
    direction = flow.get("direction") if flow.get("available") else "unknown"
    po = status.get("power_outage") if isinstance(status.get("power_outage"), dict) else {}
    grid = po.get("grid_state") if po.get("grid_state") in ("on", "off") else None
    nxt = po.get("next_outage") or {}
    cur = po.get("current_outage") or {}
    autonomy = po.get("autonomy") or {}
    next_start, next_end = parse_time(nxt.get("start")), parse_time(nxt.get("end"))
    duration = _num(nxt.get("duration_seconds"))
    if duration is None and next_start and next_end:
        duration = (next_end - next_start).total_seconds()
    insights = status.get("insights") or {}
    covers = autonomy.get("covers_next_outage")
    return View(
        poll_ok=poll_ok,
        soc=None if soc is None else int(round(soc)),
        direction=direction if direction in ("charging", "discharging", "idle") else "unknown",
        grid=grid if poll_ok else None,
        backup_w=_num(es.get("offgrid_power")),
        capacity_wh=_num(bat.get("bat_capacity")),
        rated_wh=_num(bat.get("rated_capacity")),
        temperature_c=_num(bat.get("bat_temp")),
        power_w=_num(flow.get("power_w")),
        charge_eta_s=_num(flow.get("charge_eta_seconds")),
        discharge_eta_s=_num(flow.get("discharge_eta_seconds")),
        next_start=next_start, next_end=next_end, next_start_raw=nxt.get("start"),
        next_duration_s=duration,
        current_end=parse_time(cur.get("end")),
        runtime_s=_num(autonomy.get("runtime_seconds")),
        covers=covers if isinstance(covers, bool) else None,
        load_w=_num(autonomy.get("load_w")),
        group=po.get("group"),
        schedule_published=po.get("schedule_published") if "schedule_published" in po else None,
        avg_backup_w=_num(insights.get("backup_avg_w_24h")),
        has_outage_info=bool(po),
    )


def duration(seconds) -> str:
    if seconds is None:
        return "—"
    seconds = max(1, int(seconds))
    minutes = (seconds + 59) // 60
    days, hours, mins = minutes // 1440, (minutes % 1440) // 60, minutes % 60
    if days:
        return f"{days} д {hours} год"
    if minutes >= 60:
        return f"{minutes // 60} год {mins} хв"
    return f"{minutes} хв"


def kwh(wh) -> str:
    return f"{wh / 1000.0:.2f} kWh"


def watts(w) -> str:
    return f"{abs(w):.0f} W"


def celsius(c) -> str:
    return f"{c:.1f} °C"


def local(dt: datetime) -> datetime:
    return dt.astimezone(KYIV) if KYIV else dt.astimezone()


def hhmm(dt: datetime) -> str:
    return local(dt).strftime("%H:%M")


def stamp(dt: datetime, now: datetime) -> str:
    d, n = local(dt), local(now)
    return d.strftime("%H:%M") if d.date() == n.date() else d.strftime("%d.%m %H:%M")

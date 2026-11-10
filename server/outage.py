"""Power-outage status: official schedule source first, svitlo.live aggregator as fallback.

Sources (all read-only, public):
- Lvivoblenergo (LOE) GPV schedule: https://api.loe.lviv.ua/api/menus?type=photo-grafic (Today/Tomorrow rawHtml)
- LOE emergency / planned outages by street (optional, address-level): https://power-api.loe.lviv.ua/api/pw_accidents
- YASNO (Kyiv city, Dnipro): see yasno.py
- svitlo.live proxies (all Ukraine): see svitlo.py
Grid presence is detected locally: if something is powered from the Venus backup output and it
draws power, the house is running from the battery (grid is absent).
"""
from __future__ import annotations

import html
import json
import logging
import math
import re
import threading
import urllib.parse
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from netcache import CachedJsonClient

KYIV = ZoneInfo("Europe/Kyiv")
SCHEDULE_URL = "https://api.loe.lviv.ua/api/menus?page=1&type=photo-grafic"
ACCIDENTS_URL = "https://power-api.loe.lviv.ua/api/pw_accidents"
BACKUP_DRAW_THRESHOLD_W = 5.0

_DATE_RE = re.compile(r"відключень\s+на\s+(\d{2})\.(\d{2})\.(\d{4})")
_UPDATED_RE = re.compile(r"станом\s+на\s+(\d{1,2}):(\d{2})\s+(\d{2})\.(\d{2})\.(\d{4})")
_GROUP_RE = re.compile(r"Група\s+(\d+\.\d+)\.\s*([^<]*)")
_INTERVAL_RE = re.compile(r"з\s+(\d{1,2}):(\d{2})\s+до\s+(\d{1,2}):(\d{2})")


def _iso(value: datetime) -> str:
    return value.isoformat(timespec="seconds")


def parse_gpv_html(raw_html):
    """Parse the LOE GPV text into {date, updated_at, groups: {group: [(start_iso, end_iso)]}}."""
    if not raw_html:
        return None
    text = html.unescape(raw_html)
    date_match = _DATE_RE.search(text)
    if not date_match:
        return None
    day, month, year = (int(part) for part in date_match.groups())
    base = datetime(year, month, day, tzinfo=KYIV)
    updated_at = None
    updated = _UPDATED_RE.search(text)
    if updated:
        hour, minute, u_day, u_month, u_year = (int(part) for part in updated.groups())
        updated_at = _iso(datetime(u_year, u_month, u_day, hour, minute, tzinfo=KYIV))
    groups = {}
    for group, body in _GROUP_RE.findall(text):
        intervals = []
        for h1, m1, h2, m2 in _INTERVAL_RE.findall(body):
            start = base + timedelta(hours=int(h1), minutes=int(m1))
            end = base + timedelta(hours=int(h2), minutes=int(m2))
            if end <= start:
                continue
            intervals.append((_iso(start), _iso(end)))
        groups[group] = intervals
    if not groups:
        return None
    return {"date": base.date().isoformat(), "updated_at": updated_at, "groups": groups}


def building_matches(building_names, building) -> bool:
    if not building_names or not building:
        return False
    wanted = building.strip().lower()
    return any(token.strip().lower() == wanted for token in str(building_names).split(","))


def detect_grid_state(offgrid_power_w, ongrid_power_w, device_ok: bool, threshold_w=BACKUP_DRAW_THRESHOLD_W) -> str:
    """Loads on the backup output draw power only while the grid is absent (UPS-style backup)."""
    if not device_ok or offgrid_power_w is None:
        return "unknown"
    try:
        offgrid = abs(float(offgrid_power_w))
    except (TypeError, ValueError):
        return "unknown"
    if not math.isfinite(offgrid):
        return "unknown"
    return "off" if offgrid >= float(threshold_w) else "on"


def _parse_dt(value):
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=KYIV)


def _window(kind, start: datetime, end: datetime, now: datetime, reason=None):
    remaining = max(0, round((end - max(start, now)).total_seconds()))
    return {
        "kind": kind,
        "start": _iso(start),
        "end": _iso(end),
        "duration_seconds": round((end - start).total_seconds()),
        "remaining_seconds": remaining,
        "reason": reason,
    }


def _scheduled_windows(schedules, group):
    windows = []
    for schedule in schedules or []:
        if not schedule:
            continue
        for start_iso, end_iso in schedule.get("groups", {}).get(group, []):
            start, end = _parse_dt(start_iso), _parse_dt(end_iso)
            if start and end:
                windows.append((start, end))
    windows.sort()
    merged = []
    for start, end in windows:
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def build_outage_status(*, now, group, building, schedules, accidents, grid_state, stored_wh,
                        battery_flow, expected_load_w, fetched_at, error, measured_load_w=None,
                        provider="loe", source="loe", source_name="Львівобленерго", emergency=False,
                        region=None, source_errors=None, available=True, maybe=None, source_stale=False,
                        source_detail=None):
    now = now if now.tzinfo else now.replace(tzinfo=KYIV)
    current = current_end = None
    upcoming = upcoming_start = None
    for accident in accidents or []:
        if not building_matches(accident.get("buildingNames"), building):
            continue
        start = _parse_dt(accident.get("dateEvent"))
        end = _parse_dt(accident.get("datePlanIn"))
        if not start or not end or end <= now:
            continue
        window = _window("loe_outage", start, end, now, (accident.get("koment") or "").strip() or None)
        if start <= now:
            if current_end is None or end > current_end:
                current, current_end = window, end
        elif upcoming_start is None or start < upcoming_start:
            upcoming, upcoming_start = window, start
    for start, end in _scheduled_windows(schedules, group):
        if end <= now:
            continue
        window = _window("schedule", start, end, now)
        if start <= now:
            if current is None:
                current, current_end = window, end
        elif upcoming_start is None or start < upcoming_start:
            upcoming, upcoming_start = window, start

    maybe_windows = []
    for start_iso, end_iso in maybe or []:
        start, end = _parse_dt(start_iso), _parse_dt(end_iso)
        if start and end and end > now:
            maybe_windows.append(_window("maybe", start, end, now))
    maybe_windows.sort(key=lambda w: w["start"])
    published = [schedule for schedule in schedules or [] if schedule]
    autonomy = _autonomy(grid_state, stored_wh, battery_flow, expected_load_w, current or upcoming, measured_load_w)
    return {
        "available": available,
        "provider": provider,
        "source": source,
        "source_name": source_name,
        "source_detail": source_detail,
        "region": region,
        "emergency": bool(emergency),
        "group": group,
        "grid_state": grid_state,
        "grid_state_basis": "backup_output_power",
        "current_outage": current,
        "next_outage": upcoming,
        "maybe_outages": maybe_windows,
        "schedule_published": bool(published),
        "schedule_dates": [schedule["date"] for schedule in published],
        "schedule_updated_at": max((s["updated_at"] for s in published if s.get("updated_at")), default=None),
        "autonomy": autonomy,
        "fetched_at": fetched_at,
        "error": error,
        "source_errors": source_errors or {},
        "source_stale": bool(source_stale),
    }


def _autonomy(grid_state, stored_wh, battery_flow, expected_load_w, outage, measured_load_w=None):
    result: dict = {"load_w": None, "load_basis": None, "stored_wh": None, "runtime_seconds": None,
                    "outage_seconds": None, "covers_next_outage": None}
    try:
        stored = float(stored_wh)
    except (TypeError, ValueError):
        return result
    if not math.isfinite(stored) or stored < 0:
        return result
    result["stored_wh"] = round(stored)
    flow = battery_flow or {}
    load, basis = None, None
    measured = flow.get("power_w")
    if grid_state == "off" and flow.get("direction") == "discharging" and measured is not None:
        load, basis = abs(float(measured)), "measured_discharge"
    elif expected_load_w is not None and float(expected_load_w) > 0:
        load, basis = float(expected_load_w), "configured_average"
    elif measured_load_w is not None and float(measured_load_w) > 0:
        # backup-output draw averaged over the last 24 h (see history.measured_backup_load)
        load, basis = float(measured_load_w), "measured_average_24h"
    if not load or not math.isfinite(load):
        if outage:
            result["outage_seconds"] = outage["remaining_seconds"]
        return result
    runtime = round(stored / load * 3600)
    result.update({"load_w": round(load, 1), "load_basis": basis, "runtime_seconds": runtime})
    if outage:
        result["outage_seconds"] = outage["remaining_seconds"]
        result["covers_next_outage"] = runtime >= outage["remaining_seconds"]
    return result


_LOE_HTTP = CachedJsonClient(ttl=240, accept="application/ld+json")


def _get_json(url, timeout=20):
    return _LOE_HTTP.get(url)


def fetch_schedules():
    payload = _get_json(SCHEDULE_URL)
    menus = payload if isinstance(payload, list) else (payload.get("hydra:member") or [])
    schedules = []
    for menu in menus:
        if not isinstance(menu, dict):
            continue
        for item in menu.get("menuItems") or []:
            if isinstance(item, dict) and item.get("name") in ("Today", "Tomorrow"):
                parsed = parse_gpv_html(item.get("rawHtml"))
                if parsed:
                    schedules.append(parsed)
    return schedules


def fetch_accidents(otg_id, city_id, street_id):
    params = {"page": 1, "otg.id": otg_id, "city.id": city_id, "street.id": street_id}
    query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    payload = _get_json(f"{ACCIDENTS_URL}?{query}")
    members = payload if isinstance(payload, list) else payload.get("hydra:member", [])
    return [m for m in members if isinstance(m, dict)]


class LoeFetcher:
    """Lvivoblenergo: GPV schedule for the queue + optional address-level accidents."""
    name = "loe"
    label = "Львівобленерго"

    def __init__(self, otg_id=None, city_id=None, street_id=None):
        self.otg_id, self.city_id, self.street_id = otg_id, city_id, street_id

    def fetch(self, now=None) -> dict:
        schedules = fetch_schedules()
        accidents = []
        if self.city_id is not None and self.street_id is not None:
            try:
                accidents = fetch_accidents(self.otg_id, self.city_id, self.street_id)
            except Exception as exc:  # accidents are optional extra detail
                logging.warning("LOE accidents fetch failed: %s", exc)
        return {"schedules": schedules, "accidents": accidents, "emergency": False, "detail": None}


def _has_schedule(data):
    return bool(data and any(s for s in data.get("schedules") or []))


class OutageService:
    """Tries `fetchers` in order (official first); caches the last good answer.

    A fetcher result is used when it has a published schedule or reports an emergency;
    otherwise (error or empty) the next fetcher is tried. Accidents from earlier fetchers are kept.
    """

    def __init__(self, provider, fetchers, queue, building=None, region=None, interval=600):
        self.provider, self.fetchers = provider, list(fetchers)
        self.queue, self.building, self.region = queue, building, region
        self.interval = min(int(interval), 600)
        self.lock = threading.Lock()
        self.data = None
        self.used = None
        self.fetched_at = None
        self.error = "not_fetched_yet"
        self.source_errors = {}
        self._last_attempt = None

    def refresh_if_due(self, now=None):
        now = now or datetime.now(timezone.utc)
        if self._last_attempt and (now - self._last_attempt).total_seconds() < self.interval:
            return
        self._last_attempt = now
        errors, accidents, chosen, chosen_data, fallback = {}, [], None, None, None
        for fetcher in self.fetchers:
            try:
                data = fetcher.fetch(now=now)
            except Exception as exc:
                logging.warning("outage source %s failed: %s", fetcher.name, exc)
                errors[fetcher.name] = "region_not_found" if isinstance(exc, LookupError) and \
                    str(exc).strip("'") in ("region_not_found", "queue_not_found") else type(exc).__name__
                continue
            accidents.extend(data.get("accidents") or [])
            if fallback is None:
                fallback = (fetcher, data)
            if _has_schedule(data) or data.get("emergency"):
                chosen, chosen_data = fetcher, data
                break
        if chosen is None and fallback is not None:
            chosen, chosen_data = fallback  # reachable but nothing published: report the first answer
        with self.lock:
            self.source_errors = errors
            if chosen is None:
                self.error = next(iter(errors.values()), "no_source") if errors else "no_source"
                return
            self.data = {**chosen_data, "accidents": accidents}
            self.used = chosen
            self.fetched_at = _iso(now.astimezone(KYIV))
            self.error = None

    def status(self, *, grid_state, stored_wh, battery_flow, expected_load_w, measured_load_w=None, now=None):
        with self.lock:
            data, used = self.data or {}, self.used
            fetched_at, error, source_errors = self.fetched_at, self.error, dict(self.source_errors)
        return build_outage_status(
            now=(now or datetime.now(KYIV)), group=self.queue, building=self.building,
            schedules=list(data.get("schedules") or []), accidents=list(data.get("accidents") or []),
            grid_state=grid_state, stored_wh=stored_wh, battery_flow=battery_flow,
            expected_load_w=expected_load_w, fetched_at=fetched_at, error=error,
            measured_load_w=measured_load_w, provider=self.provider,
            source=used.name if used else None, source_name=used.label if used else None,
            emergency=bool(data.get("emergency")), region=self.region, source_errors=source_errors,
            maybe=list(data.get("maybe") or []), source_stale=bool(data.get("stale")),
            source_detail=data.get("detail"),
        )


class OffService:
    """No schedule source configured: still report local grid state and battery autonomy."""
    fetchers = ()

    def refresh_if_due(self, now=None):
        return

    def status(self, *, grid_state, stored_wh, battery_flow, expected_load_w, measured_load_w=None, now=None):
        return build_outage_status(
            now=(now or datetime.now(KYIV)), group=None, building=None, schedules=[], accidents=[],
            grid_state=grid_state, stored_wh=stored_wh, battery_flow=battery_flow,
            expected_load_w=expected_load_w, fetched_at=None, error=None, measured_load_w=measured_load_w,
            provider="off", source=None, source_name=None, available=False,
        )


def build_outage_service(settings, svitlo_client=None, ua_client=None):
    """auto: direct official source (LOE / YASNO) -> ua-outages aggregate -> svitlo.live."""
    from regions import REGIONS, resolve_region, sources_for
    from svitlo import SvitloFetcher
    from uaoutages import UaOutagesFetcher
    from yasno import YasnoFetcher
    provider, queue = settings.outage_provider, settings.outage_queue
    region = resolve_region(settings.outage_region) if settings.outage_region else None
    if provider == "off" or not queue:
        return OffService()
    svitlo_slug = (REGIONS.get(region) or {}).get("svitlo") or settings.outage_region
    loe = lambda: LoeFetcher(settings.loe_otg_id, settings.loe_city_id, settings.loe_street_id)  # noqa: E731
    make = {
        "loe": loe,
        "yasno": lambda: YasnoFetcher(region, queue),
        "ua-outages": lambda: UaOutagesFetcher(region, queue, client=ua_client),
        "svitlo": lambda: SvitloFetcher(svitlo_slug, queue, client=svitlo_client),
    }
    if provider == "auto":
        names = sources_for(region)
    else:
        names = [provider]
    fetchers = [make[name]() for name in names if name in make]
    if not fetchers:
        return OffService()
    return OutageService(provider, fetchers, queue=queue, building=settings.loe_building or None, region=region)

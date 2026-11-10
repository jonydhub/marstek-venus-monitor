"""Official YASNO planned-outage schedule (Kyiv city, Dnipro) — public read-only JSON API.

GET https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/{regionId}/dsos/{dsoId}/planned-outages
-> {queue: {"today"|"tomorrow": {"slots": [{"start": min, "end": min, "type": "Definite"|"NotPlanned"}],
                                  "date": iso, "status": "ScheduleApplies"|"WaitingForSchedule"|"EmergencyShutdowns"},
            "updatedOn": iso}}
Region/DSO ids: GET .../public/shutdowns/addresses/v2/regions.
"""
from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from netcache import CachedJsonClient

KYIV = ZoneInfo("Europe/Kyiv")
BASE_URL = "https://app.yasno.ua/api/blackout-service/public/shutdowns"
# Canonical region id (see regions.py) -> YASNO ids (from /addresses/v2/regions).
YASNO_REGIONS = {
    "kyiv": {"region_id": 25, "dso_id": 902, "name_ua": "Київ (ДТЕК Київські електромережі)"},
    "dnipro": {"region_id": 3, "dso_id": 301, "name_ua": "Дніпро (ДТЕК Дніпровські електромережі, ДнЕМ)"},
    "dnipro-cek": {"region_id": 3, "dso_id": 303, "name_ua": "Дніпро (ЦЕК)"},
}
OUTAGE_SLOT_TYPES = {"Definite"}


def planned_outages_url(region_id, dso_id):
    return f"{BASE_URL}/regions/{region_id}/dsos/{dso_id}/planned-outages"


def _parse_dt(value):
    try:
        parsed = datetime.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=KYIV)


def _iso(value):
    return value.astimezone(KYIV).isoformat(timespec="seconds")


def _day_windows(day):
    base = _parse_dt(day.get("date"))
    if base is None:
        return None, []
    base = base.astimezone(KYIV).replace(hour=0, minute=0, second=0, microsecond=0)
    spans = []
    for slot in day.get("slots") or []:
        if not isinstance(slot, dict) or slot.get("type") not in OUTAGE_SLOT_TYPES:
            continue
        try:
            start, end = int(slot["start"]), int(slot["end"])
        except (KeyError, TypeError, ValueError):
            continue
        if end <= start:
            continue
        if spans and start <= spans[-1][1]:
            spans[-1] = (spans[-1][0], max(spans[-1][1], end))
        else:
            spans.append((start, end))
    windows = [(_iso(base + timedelta(minutes=a)), _iso(base + timedelta(minutes=b))) for a, b in sorted(spans)]
    return base.date().isoformat(), windows


def parse_planned_outages(payload, queue) -> dict:
    """Common schedule shape: {schedules: [{date, updated_at, groups:{queue:[(start,end)]}}], emergency}."""
    entry = (payload or {}).get(queue) if isinstance(payload, dict) else None
    if not isinstance(entry, dict):
        raise LookupError("queue_not_found")
    updated = _parse_dt(entry.get("updatedOn"))
    updated_at = _iso(updated) if updated else None
    schedules, emergency = [], False
    for key in ("today", "tomorrow"):
        day = entry.get(key)
        if not isinstance(day, dict):
            continue
        status = day.get("status")
        if status == "EmergencyShutdowns":
            emergency = True
            continue
        if status != "ScheduleApplies":
            continue  # WaitingForSchedule etc. = not published yet
        date_iso, windows = _day_windows(day)
        if date_iso:
            schedules.append({"date": date_iso, "updated_at": updated_at, "groups": {queue: windows}})
    return {"schedules": schedules, "emergency": emergency}


class YasnoFetcher:
    name = "yasno"
    label = "YASNO"

    def __init__(self, region, queue, client=None):
        if region not in YASNO_REGIONS:
            raise ValueError(f"YASNO does not cover region {region!r}")
        self.region, self.queue = region, queue
        self.ids = YASNO_REGIONS[region]
        self.client = client or CachedJsonClient(ttl=300)

    def fetch(self, now=None) -> dict:
        payload = self.client.get(planned_outages_url(self.ids["region_id"], self.ids["dso_id"]))
        parsed = parse_planned_outages(payload, self.queue)
        return {**parsed, "accidents": [],
                "detail": f"regions/{self.ids['region_id']}/dsos/{self.ids['dso_id']}"}

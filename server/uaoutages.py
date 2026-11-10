"""Official operator schedules for ~22 oblasts via the hukpo/ua-outages static aggregate (MIT).

https://hukpo.github.io/ua-outages/data/now.json is rebuilt about every 15 minutes by the project's
GitHub Action, which reads each grid operator directly. Compact format per region:
  {id, name, operator, website, ok, message, updatedAt, fetchedAt, stale?, groups,
   days: {YYYY-MM-DD: {group: {s, i: [[startMin, endMin, kind]]}}}}
s: 's' scheduled, 'n' no outages, 'e' emergency (schedule not applied), 'u' unknown / not published.
kind: 0 = power off, 1 = possible outage ("можливе відключення"). Minutes are from Kyiv midnight.
"""
from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from netcache import CachedJsonClient

KYIV = ZoneInfo("Europe/Kyiv")
NOW_URL = "https://hukpo.github.io/ua-outages/data/now.json"


def _iso(base, minutes):
    return (base + timedelta(minutes=int(minutes))).isoformat(timespec="seconds")


def _merge(spans):
    merged = []
    for start, end in sorted(spans):
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def parse_region_days(entry, queue, today) -> dict:
    """Common schedule shape plus `maybe` windows for one region entry and queue."""
    schedules, maybe, emergency = [], [], False
    updated_at = entry.get("updatedAt")
    for date_iso, groups in sorted((entry.get("days") or {}).items()):
        if date_iso < today or not isinstance(groups, dict):
            continue
        day = groups.get(queue)
        if not isinstance(day, dict):
            continue
        status = day.get("s")
        if status == "e":
            emergency = True
            continue
        if status not in ("s", "n"):
            continue
        base = datetime.fromisoformat(date_iso).replace(tzinfo=KYIV)
        off, possible = [], []
        for item in day.get("i") or []:
            try:
                start, end = int(item[0]), int(item[1])
                kind = int(item[2]) if len(item) > 2 else 0
            except (TypeError, ValueError, IndexError):
                continue
            if end > start:
                (possible if kind == 1 else off).append((start, end))
        schedules.append({"date": date_iso, "updated_at": updated_at,
                          "groups": {queue: [(_iso(base, a), _iso(base, b)) for a, b in _merge(off)]}})
        maybe.extend((_iso(base, a), _iso(base, b)) for a, b in _merge(possible))
    return {"schedules": schedules, "maybe": maybe, "emergency": emergency}


class UaOutagesFetcher:
    name = "ua-outages"
    label = "ua-outages"

    def __init__(self, region, queue, client=None):
        self.region, self.queue = region, queue
        self.client = client or CachedJsonClient(ttl=600)

    def fetch(self, now=None) -> dict:
        now = (now or datetime.now(KYIV)).astimezone(KYIV)
        payload = self.client.get(NOW_URL) or {}
        entry = next((r for r in payload.get("regions", []) if isinstance(r, dict) and r.get("id") == self.region), None)
        if entry is None:
            raise LookupError("region_not_found")
        if not entry.get("ok"):
            raise OSError(entry.get("message") or "operator_unavailable")
        groups = entry.get("groups") or []
        if groups and self.queue not in groups:
            raise LookupError("queue_not_found")
        parsed = parse_region_days(entry, self.queue, today=now.date().isoformat())
        return {**parsed, "accidents": [], "stale": bool(entry.get("stale")),
                "detail": entry.get("operator")}

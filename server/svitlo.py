"""All-Ukraine outage schedules via the public svitlo.live proxies (aggregator, fallback source).

Format and primary/fallback rules follow the MIT-licensed Home Assistant integration
chaichuk/svitlo_live: regions[].schedule[queue][YYYY-MM-DD]["HH:MM"] = code, 30-minute slots,
code 1 = power on, 2 = power off, anything else = unknown.
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from netcache import CachedJsonClient

KYIV = ZoneInfo("Europe/Kyiv")
PRIMARY_URL = "https://dtek-api.svitlo-proxy.workers.dev/"  # newer worker, may wrap JSON in "body", has "emergency"
FALLBACK_URL = "https://svitlo-proxy.svitlo-proxy.workers.dev"
SOURCE_NAMES = {True: "dtek-api", False: "svitlo-proxy"}

# Old proxy slug -> new proxy slug.
API_REGION_MAP = {
    "harkivska-oblast": "kharkivska-oblast",
    "hmelnitska-oblast": "khmelnytska-oblast",
    "chernigivska-oblast": "chernihivska-oblast",
    "jitomirska-oblast": "zhytomyrska-oblast",
}
REVERSE_API_REGION_MAP = {v: k for k, v in API_REGION_MAP.items()}
DNIPRO_REGIONS = {"dnipro-dnem", "dnipro-cek", "dnipro-city"}
# Regions whose primary source is the newer dtek-api worker.
NEW_API_REGIONS = {
    "kyiv", "kiivska-oblast", "odeska-oblast", "dnipro-dnem", "dnipro-cek", "dnipro-city",
    "dnipropetrovska-oblast", "lvivska-oblast", "chernivetska-oblast", "kharkivska-oblast",
    "poltavska-oblast", "cherkaska-oblast", "chernihivska-oblast", "khmelnytska-oblast",
    "ivano-frankivska-oblast", "rivnenska-oblast", "ternopilska-oblast", "zakarpatska-oblast",
    "zhytomyrska-oblast", "sumska-oblast",
}


def unwrap(payload) -> dict:
    if not isinstance(payload, dict):
        return {}
    body = payload.get("body")
    if isinstance(body, str):
        try:
            inner = json.loads(body)
        except json.JSONDecodeError:
            return {}
        if isinstance(inner, dict):
            if "timestamp" in payload and "timestamp" not in inner:
                inner["timestamp"] = payload["timestamp"]
            return inner
        return {}
    return payload


def slots_to_intervals(date_iso, slots) -> list:
    """Merge consecutive "off" (code 2) half-hour slots into (start_iso, end_iso) windows."""
    base = datetime.fromisoformat(date_iso).replace(tzinfo=KYIV)
    windows, start = [], None
    for index in range(48):
        label = f"{index // 2:02d}:{30 * (index % 2):02d}"
        try:
            off = int((slots or {}).get(label, 0)) == 2
        except (TypeError, ValueError):
            off = False
        if off and start is None:
            start = index
        elif not off and start is not None:
            windows.append((start, index))
            start = None
    if start is not None:
        windows.append((start, 48))
    return [((base + timedelta(minutes=30 * a)).isoformat(timespec="seconds"),
             (base + timedelta(minutes=30 * b)).isoformat(timespec="seconds")) for a, b in windows]


def score_schedule(schedule, date_iso) -> int:
    """2 = has on/off slots, 1 = date present but unknown, 0 = no data for the date."""
    if not schedule or date_iso not in schedule:
        return 0
    slots = schedule[date_iso]
    if isinstance(slots, dict) and any(v in (1, 2) for v in slots.values()):
        return 2
    return 1


def candidate_keys(region, is_new) -> list:
    keys = []
    if is_new:
        if region in API_REGION_MAP:
            keys.append(API_REGION_MAP[region])
        keys.append(region)
    else:
        if region in REVERSE_API_REGION_MAP:
            keys.append(REVERSE_API_REGION_MAP[region])
        if region in DNIPRO_REGIONS:
            keys.append("dnipropetrovska-oblast")
        keys.append(region)
        if region in API_REGION_MAP:
            keys.append(API_REGION_MAP[region])
    seen = set()
    return [k for k in keys if not (k in seen or seen.add(k))]


def find_region(data, keys, queue):
    """First match in candidate order (the old proxy lists both oblast and city entries)."""
    regions = {r.get("cpu"): r for r in (data or {}).get("regions", []) if isinstance(r, dict)}
    for key in keys:
        region = regions.get(key)
        if region is not None:
            return region, key, ((region.get("schedule") or {}).get(queue) or {})
    return None, None, None


def select_schedule(region, queue, loader, today, tomorrow):
    """Pick the source per the svitlo_live coordinator rule. `loader(is_new)` returns unwrapped data.

    Returns {source, region_key, name_ua, emergency, days: {date: slots}, timestamp} or None.
    """
    mapped = API_REGION_MAP.get(region)
    primary_is_new = region in NEW_API_REGIONS or mapped in NEW_API_REGIONS
    key = mapped if mapped and primary_is_new else region
    cache = {}

    def load(is_new):
        if is_new not in cache:
            cache[is_new] = loader(is_new) or {}
        return cache[is_new]

    prim_reg, prim_key, prim_sched = find_region(load(primary_is_new), candidate_keys(key, primary_is_new), queue)
    prim_score = score_schedule(prim_sched, today)
    prim_emergency = bool(prim_reg and prim_reg.get("emergency"))
    fall_is_new = not primary_is_new
    fall_reg = fall_key = fall_sched = None
    fall_score = 0
    if (prim_score < 2 and not prim_emergency) or not prim_reg:
        fall_reg, fall_key, fall_sched = find_region(load(fall_is_new), candidate_keys(key, fall_is_new), queue)
        fall_score = score_schedule(fall_sched, today)

    if (fall_score > prim_score and not prim_emergency) or (not prim_reg and fall_reg):
        used = (fall_is_new, fall_reg, fall_key, fall_sched)
        other_is_new, other_sched = primary_is_new, prim_sched
    else:
        used = (primary_is_new, prim_reg, prim_key, prim_sched)
        other_is_new, other_sched = fall_is_new, fall_sched
    used_is_new, used_reg, used_key, used_sched = used
    if not used_reg:
        return None

    days = {}
    for date_iso in (today, tomorrow):
        if (used_sched or {}).get(date_iso):
            days[date_iso] = used_sched[date_iso]
    if score_schedule(used_sched, tomorrow) < 2 and not prim_emergency:
        if other_sched is None:
            _, _, other_sched = find_region(load(other_is_new), candidate_keys(key, other_is_new), queue)
        if other_sched and score_schedule(other_sched, tomorrow) == 2:
            days[tomorrow] = other_sched[tomorrow]

    emergency = bool((used_reg or {}).get("emergency") or prim_emergency or (fall_reg or {}).get("emergency"))
    return {
        "source": SOURCE_NAMES[used_is_new],
        "region_key": used_key,
        "name_ua": used_reg.get("name_ua"),
        "emergency": emergency,
        "days": days,
        "timestamp": load(used_is_new).get("timestamp"),
    }


def _natural(queue):
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", queue)]


def _normalize_name(name, slug):
    if slug.endswith("-oblast") and "область" not in name.lower():
        return f"{name} область"
    return name


def build_catalog(new_data, old_data) -> list:
    """Merged region list for setup UIs: [{id, name_ua, queues}] sorted by name."""
    merged = {}
    for region in (new_data or {}).get("regions", []):
        cpu = region.get("cpu")
        if not cpu or (cpu not in NEW_API_REGIONS and API_REGION_MAP.get(cpu, cpu) not in NEW_API_REGIONS):
            continue
        merged[cpu] = {"id": cpu, "name_ua": _normalize_name(region.get("name_ua") or cpu, cpu),
                       "queues": list((region.get("schedule") or {}).keys())}
    for region in (old_data or {}).get("regions", []):
        cpu = region.get("cpu")
        schedule = region.get("schedule")
        if not cpu or schedule is None:
            continue
        target = API_REGION_MAP.get(cpu, cpu)
        entry = merged.setdefault(target, {"id": target, "name_ua": _normalize_name(region.get("name_ua") or target, target),
                                           "queues": []})
        for queue in schedule:
            if queue not in entry["queues"]:
                entry["queues"].append(queue)
    for entry in merged.values():
        entry["queues"] = sorted(entry["queues"], key=_natural)
    return sorted(merged.values(), key=lambda r: r["name_ua"])


class SvitloClient:
    def __init__(self, opener=None, ttl=600, clock=None):
        kwargs = {"ttl": ttl}
        if opener is not None:
            kwargs["opener"] = opener
        if clock is not None:
            kwargs["clock"] = clock
        self.http = CachedJsonClient(**kwargs)

    def get(self, is_new) -> dict:
        return unwrap(self.http.get(PRIMARY_URL if is_new else FALLBACK_URL))

    def catalog(self) -> list:
        return build_catalog(self.get(True), self.get(False))


def _timestamp_iso(value):
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return None
    if seconds > 1e11:  # milliseconds
        seconds /= 1000.0
    return datetime.fromtimestamp(seconds, timezone.utc).astimezone(KYIV).isoformat(timespec="seconds")


class SvitloFetcher:
    name = "svitlo"
    label = "svitlo.live"

    def __init__(self, region, queue, client=None):
        self.region, self.queue = region, queue
        self.client = client or SvitloClient()

    def fetch(self, now=None) -> dict:
        now = (now or datetime.now(KYIV)).astimezone(KYIV)
        today = now.date().isoformat()
        tomorrow = (now.date() + timedelta(days=1)).isoformat()
        selected = select_schedule(self.region, self.queue, self.client.get, today, tomorrow)
        if selected is None:
            raise LookupError("region_not_found")
        updated_at = _timestamp_iso(selected.get("timestamp"))
        schedules = []
        for date_iso, slots in sorted(selected["days"].items()):
            if score_schedule({date_iso: slots}, date_iso) < 2:
                continue  # all-unknown day = schedule not published
            schedules.append({"date": date_iso, "updated_at": updated_at,
                              "groups": {self.queue: slots_to_intervals(date_iso, slots)}})
        return {"schedules": schedules, "accidents": [], "emergency": selected["emergency"],
                "detail": selected["source"]}

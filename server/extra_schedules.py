"""Extra outage schedules («Робота», «Батьки», another place) for the dashboard: GET /api/schedule?region=&queue=.

Display only — no battery, no alerts. Each (region, queue) gets its own OutageService with the same sources as the
station's own schedule (official operator first, then ua-outages), cached and refreshed at most every 10 minutes.
The first request for a key fetches synchronously; later ones answer from the cache and refresh in the background.
"""
from __future__ import annotations

import logging
import re
import threading
from collections import OrderedDict
from datetime import datetime, timezone
from types import SimpleNamespace

from outage import KYIV, build_outage_service
from regions import REGIONS, resolve_region

MAX_KEYS = 24
_QUEUE_RE = re.compile(r"^[0-9]{1,2}(\.[0-9]{1,2})?$")
# the fields the web schedule rows read (scheduleDays / scheduleNowText); battery/autonomy fields are dropped
FIELDS = ("available", "region", "group", "source_name", "emergency", "current_outage", "next_outage", "maybe_outages",
          "schedule_published", "schedule_dates", "schedule_updated_at", "windows", "fetched_at", "error",
          "schedule_unavailable")


def normalize(region, queue):
    """(region_id, queue) or raises ValueError for an unknown region / malformed queue."""
    rid = resolve_region(region)
    q = (queue or "").strip()
    if not rid or not _QUEUE_RE.match(q):
        raise ValueError("bad_request")
    known = (REGIONS.get(rid) or {}).get("queues")
    if known and q not in known:
        raise ValueError("bad_request")
    return rid, q


class ExtraSchedules:
    def __init__(self, ua_client=None, factory=None, max_keys=MAX_KEYS):
        self.ua_client = ua_client
        self.factory = factory or self._service
        self.max_keys = max_keys
        self.lock = threading.Lock()
        self.services: OrderedDict = OrderedDict()
        self.refreshing: set = set()

    def _service(self, region, queue):
        settings = SimpleNamespace(outage_provider="auto", outage_region=region, outage_queue=queue, loe_building="",
                                   loe_otg_id=None, loe_city_id=None, loe_street_id=None)
        return build_outage_service(settings, ua_client=self.ua_client)

    def _get_service(self, key):
        with self.lock:
            svc = self.services.get(key)
            if svc is None:
                svc = self.factory(*key)
                self.services[key] = svc
                while len(self.services) > self.max_keys:
                    self.services.popitem(last=False)
            else:
                self.services.move_to_end(key)
            return svc

    def _refresh(self, key, svc, now):
        try:
            svc.refresh_if_due(now)
        except Exception as exc:  # never let one source break the endpoint
            logging.warning("extra schedule %s refresh failed: %s", key, exc)
        finally:
            with self.lock:
                self.refreshing.discard(key)

    def get(self, region, queue, now=None):
        key = normalize(region, queue)
        svc = self._get_service(key)
        now = now or datetime.now(timezone.utc)
        if getattr(svc, "data", None) is None and getattr(svc, "fetchers", ()):
            self._refresh(key, svc, now)  # first answer for this key: wait for it
        else:
            with self.lock:
                start = key not in self.refreshing
                self.refreshing.add(key)
            if start:
                threading.Thread(target=self._refresh, args=(key, svc, now), name="extra-schedule", daemon=True).start()
        status = svc.status(grid_state="unknown", stored_wh=None, battery_flow={}, expected_load_w=None,
                            now=now.astimezone(KYIV))
        out = {k: status.get(k) for k in FIELDS}
        out["region"], out["group"] = key
        out["region_name"] = (REGIONS.get(key[0]) or {}).get("name_ua") or key[0]
        return out

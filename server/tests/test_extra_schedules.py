import time
import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from extra_schedules import ExtraSchedules, normalize
from outage import OffService, OutageService

KYIV = ZoneInfo("Europe/Kyiv")
NOW = datetime(2026, 11, 10, 19, 0, tzinfo=KYIV)
WINDOW = [{"date": "2026-11-10", "updated_at": "2026-11-10T06:00:00+02:00",
           "groups": {"2.1": [("2026-11-10T20:00:00+02:00", "2026-11-10T22:00:00+02:00")]}}]


class Fake:
    name, label = "loe", "Львівобленерго"

    def __init__(self):
        self.calls = 0

    def fetch(self, now=None):
        self.calls += 1
        return {"schedules": WINDOW, "accidents": [], "emergency": False, "detail": None}


class ExtraScheduleTests(unittest.TestCase):
    def make(self, **kw):
        self.fetchers = []

        def factory(region, queue):
            f = Fake()
            self.fetchers.append(f)
            return OutageService("auto", [f], queue=queue, region=region)
        return ExtraSchedules(factory=factory, **kw)

    def test_first_request_fetches_and_returns_schedule_fields_only(self):
        extra = self.make()
        r = extra.get("lviv", "2.1", now=NOW)
        self.assertEqual(r["region"], "lviv")
        self.assertEqual(r["region_name"], "Львівська область")
        self.assertEqual(r["group"], "2.1")
        self.assertEqual(r["source_name"], "Львівобленерго")
        self.assertTrue(r["available"])
        self.assertTrue(r["schedule_published"])
        self.assertEqual(r["schedule_dates"], ["2026-11-10"])
        self.assertEqual(r["next_outage"]["start"], "2026-11-10T20:00:00+02:00")
        self.assertEqual([w["kind"] for w in r["windows"]], ["off"])
        self.assertNotIn("autonomy", r)
        self.assertNotIn("grid_state", r)

    def test_cached_per_key_and_legacy_slug_maps_to_same_key(self):
        extra = self.make()
        extra.get("lviv", "2.1", now=NOW)
        extra.get("lvivska-oblast", "2.1", now=NOW)  # alias → same service, refresh not due (10 min)
        time.sleep(0.05)
        self.assertEqual(len(self.fetchers), 1)
        self.assertEqual(self.fetchers[0].calls, 1)
        extra.get("lviv", "3.1", now=NOW)
        self.assertEqual(len(self.fetchers), 2)

    def test_bad_region_or_queue_rejected(self):
        for region, queue in [("mars", "1.1"), ("lviv", ""), ("lviv", "1.1; rm"), ("lviv", "9.9"), (None, None)]:
            with self.assertRaises(ValueError, msg=(region, queue)):
                normalize(region, queue)
        self.assertEqual(normalize("chernivtsi", "12"), ("chernivtsi", "12"))

    def test_lru_caps_number_of_keys(self):
        extra = self.make(max_keys=2)
        for q in ("1.1", "1.2", "2.1"):
            extra.get("lviv", q, now=NOW)
        self.assertEqual(list(extra.services), [("lviv", "1.2"), ("lviv", "2.1")])

    def test_region_without_source_is_unavailable(self):
        extra = ExtraSchedules(factory=lambda region, queue: OffService(region=region, unavailable=True))
        r = extra.get("lviv", "1.1", now=NOW)
        self.assertFalse(r["available"])
        self.assertTrue(r["schedule_unavailable"])


if __name__ == "__main__":
    unittest.main()

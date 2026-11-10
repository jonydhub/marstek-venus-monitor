import json
import unittest
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from yasno import YASNO_REGIONS, YasnoFetcher, parse_planned_outages, planned_outages_url

KYIV = ZoneInfo("Europe/Kyiv")
FIXTURES = Path(__file__).resolve().parent / "fixtures"
# Real responses captured 2026-11-10 (read-only GET) from app.yasno.ua public blackout-service.
KYIV_EMERGENCY = json.loads((FIXTURES / "yasno_kyiv_25_902.json").read_text(encoding="utf-8"))
DNIPRO = json.loads((FIXTURES / "yasno_dnipro_3_301.json").read_text(encoding="utf-8"))
REGIONS = json.loads((FIXTURES / "yasno_regions.json").read_text(encoding="utf-8"))


class MappingTests(unittest.TestCase):
    def test_region_ids_match_yasno_catalog(self):
        catalog = {(r["id"], d["id"]) for r in REGIONS for d in r["dsos"]}
        for slug, ids in YASNO_REGIONS.items():
            with self.subTest(slug=slug):
                self.assertIn((ids["region_id"], ids["dso_id"]), catalog)
        self.assertEqual(YASNO_REGIONS["kyiv"]["region_id"], 25)
        self.assertEqual(YASNO_REGIONS["kyiv"]["dso_id"], 902)

    def test_url(self):
        self.assertEqual(
            planned_outages_url(25, 902),
            "https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/25/dsos/902/planned-outages",
        )


class ParseTests(unittest.TestCase):
    def test_definite_slots_become_windows_for_applied_schedule_only(self):
        result = parse_planned_outages(DNIPRO, "3.1")
        self.assertFalse(result["emergency"])
        self.assertEqual(result["schedules"], [{
            "date": "2026-11-10",
            "updated_at": "2026-11-10T06:23:07+02:00",
            "groups": {"3.1": [
                ("2026-11-10T00:00:00+02:00", "2026-11-10T01:30:00+02:00"),
                ("2026-11-10T08:00:00+02:00", "2026-11-10T12:00:00+02:00"),
                ("2026-11-10T18:30:00+02:00", "2026-11-10T22:30:00+02:00"),
            ]},
        }])  # tomorrow is WaitingForSchedule -> not published

    def test_emergency_status_has_no_schedule(self):
        result = parse_planned_outages(KYIV_EMERGENCY, "1.1")
        self.assertTrue(result["emergency"])
        self.assertEqual(result["schedules"], [])

    def test_unknown_queue_raises(self):
        with self.assertRaises(LookupError):
            parse_planned_outages(DNIPRO, "9.9")

    def test_schedule_applies_without_definite_slots_is_published_with_no_windows(self):
        payload = {"1.1": {"today": {"slots": [{"start": 0, "end": 1440, "type": "NotPlanned"}],
                                     "date": "2026-11-10T00:00:00+02:00", "status": "ScheduleApplies"},
                           "updatedOn": "2026-11-10T04:23:07+00:00"}}
        result = parse_planned_outages(payload, "1.1")
        self.assertEqual(result["schedules"][0]["groups"], {"1.1": []})


class FetcherTests(unittest.TestCase):
    def test_fetch_uses_client_and_region_ids(self):
        seen = []

        class Client:
            def get(self, url):
                seen.append(url)
                return DNIPRO

        data = YasnoFetcher("dnipro", "1.1", client=Client()).fetch(now=datetime(2026, 11, 10, tzinfo=KYIV))
        self.assertEqual(seen, [planned_outages_url(3, 301)])
        self.assertEqual(data["detail"], "regions/3/dsos/301")
        self.assertEqual(len(data["schedules"]), 1)


if __name__ == "__main__":
    unittest.main()

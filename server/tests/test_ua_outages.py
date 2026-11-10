import json
import unittest
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from regions import REGIONS, build_region_catalog, resolve_region
from uaoutages import NOW_URL, UaOutagesFetcher, parse_region_days

KYIV = ZoneInfo("Europe/Kyiv")
FIXTURES = Path(__file__).resolve().parent / "fixtures"
# Trimmed real https://hukpo.github.io/ua-outages/data/now.json (generated 2026-11-10T10:11Z).
NOW = json.loads((FIXTURES / "ua_outages_now.json").read_text(encoding="utf-8"))
TODAY = datetime(2026, 11, 10, 12, 0, tzinfo=KYIV)


def region(rid):
    return next(r for r in NOW["regions"] if r["id"] == rid)


class Client:
    def __init__(self, payload=NOW):
        self.payload, self.urls = payload, []

    def get(self, url):
        self.urls.append(url)
        return self.payload


class ParseTests(unittest.TestCase):
    def test_scheduled_day_off_intervals(self):
        result = parse_region_days(region("odesa"), "1.1", today="2026-11-10")
        self.assertFalse(result["emergency"])
        self.assertEqual(result["schedules"][0]["date"], "2026-11-10")
        self.assertEqual(result["schedules"][0]["updated_at"], "2026-11-10T06:44:00+02:00")
        self.assertEqual(result["schedules"][0]["groups"]["1.1"], [
            ("2026-11-10T06:30:00+02:00", "2026-11-10T10:30:00+02:00"),
            ("2026-11-10T17:00:00+02:00", "2026-11-10T21:00:00+02:00"),
        ])
        self.assertEqual(result["maybe"], [])

    def test_maybe_intervals_are_kept_separately(self):
        result = parse_region_days(region("mykolaiv"), "1.1", today="2026-11-10")
        self.assertEqual(result["schedules"][0]["groups"]["1.1"],
                         [("2026-11-10T15:00:00+02:00", "2026-11-10T21:00:00+02:00")])
        self.assertEqual(result["maybe"], [
            ("2026-11-10T14:30:00+02:00", "2026-11-10T15:00:00+02:00"),
            ("2026-11-10T21:00:00+02:00", "2026-11-10T21:30:00+02:00"),
        ])

    def test_emergency_and_unknown_days(self):
        kyiv = parse_region_days(region("kyiv"), "1.1", today="2026-11-10")
        self.assertTrue(kyiv["emergency"])
        self.assertEqual(kyiv["schedules"], [])
        dnipro = parse_region_days(region("dnipro"), "1.1", today="2026-11-10")
        self.assertEqual([s["date"] for s in dnipro["schedules"]], ["2026-11-10"])  # tomorrow 'u'

    def test_no_outages_day_is_published_without_windows(self):
        entry = {"id": "x", "ok": True, "groups": ["1.1"], "updatedAt": None,
                 "days": {"2026-11-10": {"1.1": {"s": "n", "i": []}}}}
        result = parse_region_days(entry, "1.1", today="2026-11-10")
        self.assertEqual(result["schedules"], [{"date": "2026-11-10", "updated_at": None, "groups": {"1.1": []}}])

    def test_past_days_are_ignored(self):
        result = parse_region_days(region("odesa"), "1.1", today="2026-11-11")
        self.assertEqual(result["schedules"], [])


class FetcherTests(unittest.TestCase):
    def test_fetch_reads_static_aggregate(self):
        client = Client()
        data = UaOutagesFetcher("odesa", "1.1", client=client).fetch(now=TODAY)
        self.assertEqual(client.urls, [NOW_URL])
        self.assertEqual(len(data["schedules"]), 1)
        self.assertEqual(data["detail"], "ДТЕК Одеські електромережі")

    def test_failed_region_raises_so_chain_falls_back(self):
        with self.assertRaises(OSError):
            UaOutagesFetcher("kharkiv", "1.1", client=Client()).fetch(now=TODAY)

    def test_region_without_groups_is_empty_not_error(self):
        data = UaOutagesFetcher("lviv", "1.1", client=Client()).fetch(now=TODAY)
        self.assertEqual(data["schedules"], [])
        self.assertFalse(data["emergency"])

    def test_unknown_region_and_queue(self):
        with self.assertRaises(LookupError):
            UaOutagesFetcher("atlantis", "1.1", client=Client()).fetch(now=TODAY)
        with self.assertRaises(LookupError):
            UaOutagesFetcher("odesa", "99.9", client=Client()).fetch(now=TODAY)

    def test_stale_flag_is_reported(self):
        payload = json.loads(json.dumps(NOW))
        region_entry = next(r for r in payload["regions"] if r["id"] == "odesa")
        region_entry["stale"] = True
        data = UaOutagesFetcher("odesa", "1.1", client=Client(payload)).fetch(now=TODAY)
        self.assertTrue(data["stale"])


class RegionTableTests(unittest.TestCase):
    def test_canonical_ids_follow_ua_outages(self):
        for rid in ("kyiv", "kyiv-region", "dnipro", "dnipro-cek", "odesa", "lviv", "kharkiv", "vinnytsia"):
            self.assertIn(rid, REGIONS)
        self.assertEqual(REGIONS["lviv"]["official"], "loe")
        self.assertEqual(REGIONS["kyiv"]["official"], "yasno")
        self.assertEqual(REGIONS["dnipro"]["official"], "yasno")
        self.assertIsNone(REGIONS["odesa"]["official"])
        self.assertEqual(REGIONS["lviv"]["operator"], "Львівобленерго")

    def test_legacy_slugs_are_accepted_as_aliases(self):
        self.assertEqual(resolve_region("lvivska-oblast"), "lviv")
        self.assertEqual(resolve_region("harkivska-oblast"), "kharkiv")
        self.assertEqual(resolve_region("kharkivska-oblast"), "kharkiv")
        self.assertEqual(resolve_region("kiivska-oblast"), "kyiv-region")
        self.assertEqual(resolve_region("dnipro-dnem"), "dnipro")
        self.assertEqual(resolve_region("hmelnitska-oblast"), "khmelnytskyi")
        self.assertEqual(resolve_region("vinnitska-oblast"), "vinnytsia")
        self.assertEqual(resolve_region("odesa"), "odesa")
        self.assertEqual(resolve_region("uz-lviv"), "uz-lviv")
        self.assertIsNone(resolve_region("atlantis"))

    def test_catalog_lists_sources_per_region(self):
        catalog = build_region_catalog(NOW)
        by_id = {r["id"]: r for r in catalog}
        self.assertEqual(by_id["lviv"]["sources"], ["loe", "ua-outages"])
        self.assertEqual(by_id["kyiv"]["sources"], ["yasno", "ua-outages"])
        self.assertEqual(by_id["odesa"]["sources"], ["ua-outages"])
        self.assertEqual(by_id["kharkiv"]["sources"], ["kharkiv", "ua-outages"])
        self.assertEqual(by_id["poltava"]["sources"], ["poltava", "ua-outages"])
        self.assertEqual(by_id["zaporizhzhia"]["sources"], ["zaporizhzhia", "ua-outages"])
        self.assertEqual(by_id["chernivtsi"]["sources"], ["chernivtsi", "ua-outages"])
        self.assertEqual(by_id["vinnytsia"]["sources"], [])
        self.assertEqual(by_id["vinnytsia"]["queues"], [])
        self.assertEqual(by_id["uz-lviv"]["sources"], ["ua-outages"])
        self.assertEqual(by_id["odesa"]["operator"], "ДТЕК Одеські електромережі")
        for entry in catalog:
            self.assertEqual(set(entry), {"id", "name_ua", "operator", "queues", "sources"})

    def test_catalog_queues_fall_back_to_the_static_table(self):
        # ua-outages reports kharkiv / chernivtsi as failed (no groups) from abroad
        by_id = {r["id"]: r for r in build_region_catalog(NOW)}
        self.assertEqual(by_id["kharkiv"]["queues"][:3], ["1.1", "1.2", "2.1"])
        self.assertEqual(by_id["chernivtsi"]["queues"], [str(n) for n in range(1, 13)])
        self.assertEqual({r["id"]: r for r in build_region_catalog(None)}["lviv"]["queues"][-1], "6.2")

if __name__ == "__main__":
    unittest.main()

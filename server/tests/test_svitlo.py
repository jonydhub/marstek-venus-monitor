import json
import unittest
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import svitlo
from svitlo import (
    SvitloClient,
    SvitloFetcher,
    build_catalog,
    candidate_keys,
    select_schedule,
    slots_to_intervals,
    unwrap,
)

KYIV = ZoneInfo("Europe/Kyiv")
FIXTURES = Path(__file__).resolve().parent / "fixtures"
# Trimmed real payloads captured 2026-11-10 from the two svitlo.live proxies.
DTEK = json.loads((FIXTURES / "svitlo_dtek_api.json").read_text(encoding="utf-8"))
OLD = json.loads((FIXTURES / "svitlo_proxy.json").read_text(encoding="utf-8"))
TODAY, TOMORROW = "2026-11-10", "2026-11-11"


def day(pattern):
    """48 chars, one code per 30-min slot from 00:00."""
    assert len(pattern) == 48
    return {f"{i // 2:02d}:{30 * (i % 2):02d}": int(c) for i, c in enumerate(pattern)}


def payload(regions, wrap=False):
    data = {"date_today": TODAY, "date_tomorrow": TOMORROW, "regions": regions}
    return {"body": json.dumps(data), "timestamp": 1794311740439} if wrap else data


class UnwrapTests(unittest.TestCase):
    def test_body_string_is_decoded(self):
        data = unwrap(DTEK)
        self.assertEqual(data["date_today"], TODAY)
        self.assertTrue(any(r["cpu"] == "kyiv" for r in data["regions"]))

    def test_plain_payload_passes_through(self):
        self.assertIs(unwrap(OLD), OLD)

    def test_garbage_becomes_empty(self):
        self.assertEqual(unwrap({"body": "{not json"}), {})
        self.assertEqual(unwrap(None), {})


class SlotTests(unittest.TestCase):
    def test_consecutive_off_slots_merge_into_windows(self):
        slots = day("1" * 20 + "2222" + "1" * 8 + "22" + "0" * 6 + "1" * 6 + "22")
        self.assertEqual(slots_to_intervals(TODAY, slots), [
            ("2026-11-10T10:00:00+02:00", "2026-11-10T12:00:00+02:00"),
            ("2026-11-10T16:00:00+02:00", "2026-11-10T17:00:00+02:00"),
            ("2026-11-10T23:00:00+02:00", "2026-11-11T00:00:00+02:00"),
        ])

    def test_unknown_and_on_slots_are_not_outages(self):
        self.assertEqual(slots_to_intervals(TODAY, day("0" * 24 + "1" * 20 + "3333")), [])


class CandidateKeyTests(unittest.TestCase):
    def test_slug_map_between_apis(self):
        self.assertEqual(candidate_keys("harkivska-oblast", True), ["kharkivska-oblast", "harkivska-oblast"])
        self.assertEqual(candidate_keys("kharkivska-oblast", False)[0], "harkivska-oblast")
        self.assertIn("dnipropetrovska-oblast", candidate_keys("dnipro-cek", False))
        for old, new in (("hmelnitska-oblast", "khmelnytska-oblast"),
                         ("chernigivska-oblast", "chernihivska-oblast"),
                         ("jitomirska-oblast", "zhytomyrska-oblast")):
            self.assertEqual(svitlo.API_REGION_MAP[old], new)


class SelectScheduleTests(unittest.TestCase):
    def select(self, region, queue, new=DTEK, old=OLD):
        calls = []

        def loader(is_new):
            calls.append(is_new)
            return unwrap(new if is_new else old)

        return select_schedule(region, queue, loader, TODAY, TOMORROW), calls

    def test_emergency_in_primary_keeps_primary_and_skips_fallback(self):
        result, calls = self.select("kyiv", "1.1")
        self.assertTrue(result["emergency"])
        self.assertEqual(result["source"], "dtek-api")
        self.assertEqual(calls, [True])

    def test_full_primary_schedule_is_used(self):
        new = payload([{"cpu": "lvivska-oblast", "name_ua": "Львівська область", "emergency": False,
                        "schedule": {"1.1": {TODAY: day("1" * 40 + "2222" + "1111")}}}], wrap=True)
        result, calls = self.select("lvivska-oblast", "1.1", new=new)
        self.assertEqual(result["source"], "dtek-api")
        self.assertEqual(result["days"][TODAY]["20:00"], 2)
        self.assertFalse(result["emergency"])

    def test_stale_primary_falls_back_when_fallback_scores_higher(self):
        # Real case: dtek-api only had 2026-10-17 for Lviv 1.1, the old proxy had today (all zeros).
        result, _ = self.select("lvivska-oblast", "1.1")
        self.assertEqual(result["source"], "svitlo-proxy")
        self.assertIn(TODAY, result["days"])

    def test_region_missing_in_primary_uses_fallback(self):
        result, calls = self.select("sumska-oblast", "1.1")
        self.assertEqual(result["source"], "svitlo-proxy")
        self.assertEqual(result["region_key"], "sumska-oblast")
        self.assertEqual(calls, [True, False])

    def test_old_only_region_with_mapped_slug(self):
        result, _ = self.select("vinnitska-oblast", "1.1")
        self.assertEqual(result["source"], "svitlo-proxy")
        self.assertEqual(result["days"][TODAY]["10:00"], 2)

    def test_tomorrow_is_augmented_from_other_source(self):
        new = payload([{"cpu": "odeska-oblast", "name_ua": "Одеська", "emergency": False,
                        "schedule": {"1.1": {TODAY: day("1" * 48)}}}], wrap=True)
        old = payload([{"cpu": "odeska-oblast", "name_ua": "Одеська",
                        "schedule": {"1.1": {TODAY: day("0" * 48), TOMORROW: day("2" * 4 + "1" * 44)}}}])
        result, _ = self.select("odeska-oblast", "1.1", new=new, old=old)
        self.assertEqual(result["source"], "dtek-api")
        self.assertEqual(result["days"][TOMORROW]["00:00"], 2)

    def test_unknown_region_returns_none(self):
        result, _ = self.select("atlantis", "1.1")
        self.assertIsNone(result)


class CatalogTests(unittest.TestCase):
    def test_catalog_merges_and_normalises(self):
        catalog = build_catalog(unwrap(DTEK), unwrap(OLD))
        by_id = {r["id"]: r for r in catalog}
        self.assertIn("kharkivska-oblast", by_id)        # old harkivska mapped onto new slug
        self.assertNotIn("harkivska-oblast", by_id)
        self.assertNotIn("avtonomna-respublika-krim", by_id)  # null schedule
        self.assertEqual(by_id["vinnitska-oblast"]["name_ua"], "Вінницька область")
        self.assertEqual(by_id["lvivska-oblast"]["queues"][:3], ["1.1", "1.2", "2.1"])
        self.assertEqual(by_id["chernivetska-oblast"]["queues"][:2], ["1", "2"])
        for region in catalog:
            self.assertEqual(set(region), {"id", "name_ua", "queues"})


class ClientTests(unittest.TestCase):
    def test_conditional_get_and_304_keep_cache(self):
        seen = []
        responses = [
            (200, {"ETag": '"v1"', "Last-Modified": "Tue, 10 Nov 2026 10:00:00 GMT"}, json.dumps(OLD).encode()),
            (304, {}, b""),
        ]

        def opener(url, headers, timeout):
            seen.append((url, dict(headers)))
            return responses.pop(0)

        clock = [1000.0]
        client = SvitloClient(opener=opener, ttl=600, clock=lambda: clock[0])
        self.assertEqual(len(client.get(False)["regions"]), len(OLD["regions"]))
        self.assertEqual(len(client.get(False)["regions"]), len(OLD["regions"]))  # cached, no request
        self.assertEqual(len(seen), 1)
        clock[0] += 601
        self.assertEqual(len(client.get(False)["regions"]), len(OLD["regions"]))
        self.assertEqual(seen[1][1]["If-None-Match"], '"v1"')
        self.assertEqual(seen[1][1]["If-Modified-Since"], "Tue, 10 Nov 2026 10:00:00 GMT")
        self.assertIn("User-Agent", seen[0][1])
        self.assertEqual(seen[0][0], svitlo.FALLBACK_URL)

    def test_failure_keeps_last_good_data_and_raises_when_empty(self):
        def boom(url, headers, timeout):
            raise OSError("down")
        client = SvitloClient(opener=boom, ttl=600, clock=lambda: 0.0)
        with self.assertRaises(OSError):
            client.get(True)


class FetcherTests(unittest.TestCase):
    def client(self, new=DTEK, old=OLD):
        return SvitloClient(opener=lambda u, h, t: (200, {}, json.dumps(new if "dtek" in u else old).encode()),
                            ttl=600, clock=lambda: 0.0)

    def test_fetch_returns_schedule_in_common_shape(self):
        new = payload([{"cpu": "kyiv", "name_ua": "Київ", "emergency": False,
                        "schedule": {"3.1": {TODAY: day("1" * 40 + "2222" + "1111")}}}], wrap=True)
        data = SvitloFetcher("kyiv", "3.1", client=self.client(new=new)).fetch(
            now=datetime(2026, 11, 10, 17, 0, tzinfo=KYIV))
        self.assertEqual(data["schedules"], [{
            "date": TODAY, "updated_at": "2026-11-10T13:55:40+02:00",
            "groups": {"3.1": [("2026-11-10T20:00:00+02:00", "2026-11-10T22:00:00+02:00")]},
        }])
        self.assertFalse(data["emergency"])
        self.assertEqual(data["accidents"], [])
        self.assertEqual(data["detail"], "dtek-api")

    def test_all_zero_day_is_not_published(self):
        data = SvitloFetcher("lvivska-oblast", "1.1", client=self.client()).fetch(
            now=datetime(2026, 11, 10, 12, 0, tzinfo=KYIV))
        self.assertEqual(data["schedules"], [])

    def test_emergency_flag_is_passed_through(self):
        data = SvitloFetcher("kyiv", "1.1", client=self.client()).fetch(
            now=datetime(2026, 11, 10, 12, 0, tzinfo=KYIV))
        self.assertTrue(data["emergency"])

    def test_unknown_region_raises(self):
        with self.assertRaises(LookupError):
            SvitloFetcher("atlantis", "1.1", client=self.client()).fetch()


if __name__ == "__main__":
    unittest.main()

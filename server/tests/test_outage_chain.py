import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from config import load_settings, validate
from outage import (
    OffService,
    OutageService,
    build_outage_service,
    detect_grid_state,
)

KYIV = ZoneInfo("Europe/Kyiv")
NOW = datetime(2026, 11, 10, 19, 0, tzinfo=KYIV)
WINDOW = [{"date": "2026-11-10", "updated_at": "2026-11-10T06:00:00+02:00",
           "groups": {"1.1": [("2026-11-10T20:00:00+02:00", "2026-11-10T22:00:00+02:00")]}}]


class Fake:
    def __init__(self, name, label, result=None, error=None):
        self.name, self.label, self.result, self.error, self.calls = name, label, result, error, 0

    def fetch(self, now=None):
        self.calls += 1
        if self.error:
            raise self.error
        return self.result


def data(schedules=(), accidents=(), emergency=False):
    return {"schedules": list(schedules), "accidents": list(accidents), "emergency": emergency,
            "detail": None}


def status(service, **kw):
    service.refresh_if_due(now=NOW)
    args = dict(grid_state="on", stored_wh=2560.0, battery_flow={}, expected_load_w=640.0, now=NOW)
    args.update(kw)
    return service.status(**args)


class ChainTests(unittest.TestCase):
    def test_official_source_wins_when_it_has_a_schedule(self):
        official, fallback = Fake("yasno", "YASNO", data(WINDOW)), Fake("ua-outages", "ua-outages", data())
        result = status(OutageService("auto", [official, fallback], queue="1.1", region="kyiv"))
        self.assertEqual(result["provider"], "auto")
        self.assertEqual(result["source"], "yasno")
        self.assertEqual(result["source_name"], "YASNO")
        self.assertEqual(result["region"], "kyiv")
        self.assertEqual(result["next_outage"]["start"], "2026-11-10T20:00:00+02:00")
        self.assertFalse(result["emergency"])
        self.assertEqual(fallback.calls, 0)

    def test_official_failure_falls_back_to_ua_outages(self):
        official = Fake("loe", "Львівобленерго", error=OSError("timeout"))
        fallback = Fake("ua-outages", "ua-outages", data(WINDOW))
        result = status(OutageService("auto", [official, fallback], queue="1.1"))
        self.assertEqual(result["source"], "ua-outages")
        self.assertIsNone(result["error"])
        self.assertEqual(result["source_errors"], {"loe": "OSError"})
        self.assertTrue(result["schedule_published"])

    def test_empty_official_falls_back_but_keeps_official_accidents(self):
        accident = {"dateEvent": "2026-11-10T18:30:00+02:00", "datePlanIn": "2026-11-10T21:00:00+02:00",
                    "koment": "ремонт", "buildingNames": "5, 7"}
        official = Fake("loe", "Львівобленерго", data(accidents=[accident]))
        fallback = Fake("ua-outages", "ua-outages", data(WINDOW))
        result = status(OutageService("auto", [official, fallback], queue="1.1", building="7"))
        self.assertEqual(result["source"], "ua-outages")
        self.assertEqual(result["current_outage"]["kind"], "loe_outage")

    def test_official_emergency_is_authoritative(self):
        official = Fake("yasno", "YASNO", data(emergency=True))
        fallback = Fake("ua-outages", "ua-outages", data(WINDOW))
        result = status(OutageService("auto", [official, fallback], queue="1.1"))
        self.assertEqual(result["source"], "yasno")
        self.assertTrue(result["emergency"])
        self.assertFalse(result["schedule_published"])
        self.assertEqual(fallback.calls, 0)

    def test_all_sources_failing_keeps_last_good_data(self):
        official = Fake("yasno", "YASNO", data(WINDOW))
        service = OutageService("auto", [official], queue="1.1", interval=0)
        status(service)
        official.error = OSError("down")
        result = status(service)
        self.assertEqual(result["error"], "OSError")
        self.assertEqual(result["source"], "yasno")
        self.assertTrue(result["schedule_published"])

    def test_maybe_windows_and_stale_flag_are_reported(self):
        official = Fake("ua-outages", "ua-outages", {**data(WINDOW), "stale": True,
                        "maybe": [("2026-11-10T19:30:00+02:00", "2026-11-10T20:00:00+02:00"),
                                  ("2026-11-10T10:00:00+02:00", "2026-11-10T10:30:00+02:00")]})
        result = status(OutageService("auto", [official], queue="1.1"))
        self.assertEqual(result["maybe_outages"], [{
            "kind": "maybe", "start": "2026-11-10T19:30:00+02:00", "end": "2026-11-10T20:00:00+02:00",
            "duration_seconds": 1800, "remaining_seconds": 1800, "reason": None}])
        self.assertEqual(result["next_outage"]["start"], "2026-11-10T20:00:00+02:00")  # maybe != off
        self.assertTrue(result["source_stale"])

    def test_refresh_interval_is_at_most_ten_minutes(self):
        self.assertLessEqual(OutageService("auto", [], queue="1.1").interval, 600)


class OffTests(unittest.TestCase):
    def test_off_service_keeps_grid_state_and_autonomy(self):
        result = OffService().status(grid_state="off", stored_wh=1000.0,
                                     battery_flow={"direction": "discharging", "power_w": -500.0},
                                     expected_load_w=None)
        self.assertFalse(result["available"])
        self.assertEqual(result["provider"], "off")
        self.assertIsNone(result["source"])
        self.assertEqual(result["grid_state"], "off")
        self.assertEqual(result["autonomy"]["runtime_seconds"], 7200)
        self.assertFalse(result["schedule_published"])


class BuildServiceTests(unittest.TestCase):
    def names(self, **env):
        settings = load_settings({"MARSTEK_HOST": "h", **env})
        self.assertEqual(validate(settings), [])
        service = build_outage_service(settings)
        return [f.name for f in getattr(service, "fetchers", [])], service

    def test_auto_lviv_uses_loe_then_ua_outages(self):
        names, service = self.names(MARSTEK_OUTAGE_REGION="lvivska-oblast", MARSTEK_OUTAGE_QUEUE="1.1")
        self.assertEqual(names, ["loe", "ua-outages"])
        self.assertEqual(service.region, "lviv")  # legacy slug resolved to canonical id
        self.assertEqual(self.names(MARSTEK_OUTAGE_REGION="lviv", MARSTEK_OUTAGE_QUEUE="1.1")[0],
                         ["loe", "ua-outages"])

    def test_auto_kyiv_and_dnipro_use_yasno_then_ua_outages(self):
        for region in ("kyiv", "dnipro-cek", "dnipro"):
            self.assertEqual(self.names(MARSTEK_OUTAGE_REGION=region, MARSTEK_OUTAGE_QUEUE="1.1")[0],
                             ["yasno", "ua-outages"])

    def test_auto_direct_oblenergo_regions(self):
        for region, legacy in (("kharkiv", "harkivska-oblast"), ("poltava", "poltavska-oblast"),
                               ("zaporizhzhia", "zaporizka-oblast"), ("chernivtsi", "chernivetska-oblast")):
            names, service = self.names(MARSTEK_OUTAGE_REGION=legacy, MARSTEK_OUTAGE_QUEUE="1")
            self.assertEqual(names, [region, "ua-outages"])
            self.assertEqual(service.region, region)
        labels = [f.label for f in self.names(MARSTEK_OUTAGE_REGION="kharkiv", MARSTEK_OUTAGE_QUEUE="1.1")[1].fetchers]
        self.assertEqual(labels, ["Харківобленерго", "ua-outages"])

    def test_auto_other_region_uses_ua_outages_only(self):
        self.assertEqual(self.names(MARSTEK_OUTAGE_REGION="odeska-oblast", MARSTEK_OUTAGE_QUEUE="1.1")[0],
                         ["ua-outages"])
        self.assertEqual(self.names(MARSTEK_OUTAGE_REGION="uz-lviv", MARSTEK_OUTAGE_QUEUE="1.1")[0], ["ua-outages"])

    def test_region_without_any_source_reports_schedule_unavailable(self):
        names, service = self.names(MARSTEK_OUTAGE_REGION="vinnitska-oblast", MARSTEK_OUTAGE_QUEUE="1.1")
        self.assertEqual(names, [])
        self.assertIsInstance(service, OffService)
        result = service.status(grid_state="on", stored_wh=1000.0, battery_flow={}, expected_load_w=None, now=NOW)
        self.assertTrue(result["schedule_unavailable"])
        self.assertEqual(result["provider"], "unavailable")
        self.assertEqual(result["region"], "vinnytsia")
        self.assertEqual(result["grid_state"], "on")

    def test_explicit_providers(self):
        self.assertEqual(self.names(MARSTEK_OUTAGE_PROVIDER="loe", MARSTEK_OUTAGE_QUEUE="1.1")[0], ["loe"])
        self.assertEqual(self.names(MARSTEK_OUTAGE_PROVIDER="ua-outages", MARSTEK_OUTAGE_REGION="odesa",
                                    MARSTEK_OUTAGE_QUEUE="1.1")[0], ["ua-outages"])
        self.assertEqual(self.names(MARSTEK_OUTAGE_PROVIDER="yasno", MARSTEK_OUTAGE_REGION="kyiv",
                                    MARSTEK_OUTAGE_QUEUE="1.1")[0], ["yasno"])
        self.assertEqual(self.names(MARSTEK_OUTAGE_PROVIDER="poltava", MARSTEK_OUTAGE_REGION="poltava",
                                    MARSTEK_OUTAGE_QUEUE="1.1")[0], ["poltava"])

    def test_off_and_unconfigured(self):
        self.assertIsInstance(self.names()[1], OffService)
        self.assertIsInstance(self.names(MARSTEK_OUTAGE_PROVIDER="off", MARSTEK_OUTAGE_REGION="kyiv")[1], OffService)

    def test_unknown_region_is_rejected(self):
        settings = load_settings({"MARSTEK_HOST": "h", "MARSTEK_OUTAGE_REGION": "atlantis", "MARSTEK_OUTAGE_QUEUE": "1.1"})
        self.assertTrue(any("MARSTEK_OUTAGE_REGION" in e for e in validate(settings)))

    def test_yasno_requires_supported_region(self):
        settings = load_settings({"MARSTEK_HOST": "h", "MARSTEK_OUTAGE_PROVIDER": "yasno",
                                  "MARSTEK_OUTAGE_REGION": "odeska-oblast", "MARSTEK_OUTAGE_QUEUE": "1.1"})
        self.assertTrue(any("yasno" in e.lower() for e in validate(settings)))


class ThresholdTests(unittest.TestCase):
    def test_backup_threshold_is_configurable(self):
        self.assertEqual(detect_grid_state(8, 0, True, threshold_w=10.0), "on")
        self.assertEqual(detect_grid_state(12, 0, True, threshold_w=10.0), "off")
        self.assertEqual(detect_grid_state(6, 0, True), "off")


if __name__ == "__main__":
    unittest.main()

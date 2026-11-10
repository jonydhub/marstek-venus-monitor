import json
import tempfile
import unittest
from pathlib import Path

from tests import _path  # noqa: F401
from mvm import appconfig


class LoadSaveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "MarstekVenusMonitor" / "config.json"

    def tearDown(self):
        self.tmp.cleanup()

    def test_missing_file_gives_defaults_without_personal_values(self):
        cfg = appconfig.load_config(self.path)
        self.assertEqual("", cfg["battery"]["host"])
        self.assertEqual(30000, cfg["battery"]["port"])
        self.assertEqual(30, cfg["poll_seconds"])
        self.assertFalse(cfg["phone_access"]["enabled"])
        self.assertTrue(all(cfg["alerts"].values()))
        self.assertEqual(set(appconfig.ALERT_IDS), set(cfg["alerts"]))
        self.assertFalse(appconfig.is_configured(cfg))

    def test_round_trip_is_atomic_and_utf8(self):
        cfg = appconfig.load_config(self.path)
        cfg["battery"]["host"] = "192.0.2.10"
        cfg["outage"].update(region="kyiv", queue="3.1")
        appconfig.save_config(self.path, cfg)
        self.assertFalse(list(self.path.parent.glob("*.tmp")))
        again = appconfig.load_config(self.path)
        self.assertEqual("192.0.2.10", again["battery"]["host"])
        self.assertEqual("kyiv", again["outage"]["region"])
        self.assertTrue(appconfig.is_configured(again))
        self.assertEqual(appconfig.SCHEMA_VERSION, json.loads(self.path.read_text("utf-8"))["schema"])

    def test_corrupt_file_is_kept_aside_and_defaults_used(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_text("{not json", "utf-8")
        cfg = appconfig.load_config(self.path)
        self.assertEqual("", cfg["battery"]["host"])
        self.assertTrue((self.path.parent / "config.json.bad").exists())

    def test_invalid_values_are_normalised(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_text(json.dumps({"schema": 2, "battery": {"host": " 192.0.2.10 ", "port": "70000"},
                                         "poll_seconds": 2, "backup_threshold_w": "abc",
                                         "alerts": {"light_lost": False, "bogus": True}}), "utf-8")
        cfg = appconfig.load_config(self.path)
        self.assertEqual("192.0.2.10", cfg["battery"]["host"])
        self.assertEqual(30000, cfg["battery"]["port"])
        self.assertEqual(appconfig.MIN_POLL_SECONDS, cfg["poll_seconds"])
        self.assertEqual(5.0, cfg["backup_threshold_w"])
        self.assertFalse(cfg["alerts"]["light_lost"])
        self.assertNotIn("bogus", cfg["alerts"])


class MigrationTests(unittest.TestCase):
    def test_flat_v1_config_is_migrated(self):
        cfg = appconfig.migrate({"host": "192.0.2.20", "port": 30001, "region": "lviv", "queue": "1.1",
                                 "threshold_w": 8, "expected_load_w": 120, "lan": True, "token": "abc"})
        self.assertEqual(appconfig.SCHEMA_VERSION, cfg["schema"])
        self.assertEqual({"host": "192.0.2.20", "port": 30001}, cfg["battery"])
        self.assertEqual("lviv", cfg["outage"]["region"])
        self.assertEqual("1.1", cfg["outage"]["queue"])
        self.assertEqual(8.0, cfg["backup_threshold_w"])
        self.assertEqual(120.0, cfg["expected_load_w"])
        self.assertTrue(cfg["phone_access"]["enabled"])
        self.assertEqual("abc", cfg["phone_access"]["token"])

    def test_newer_schema_is_not_downgraded(self):
        cfg = appconfig.migrate({"schema": 99, "battery": {"host": "192.0.2.1"}})
        self.assertEqual(99, cfg["schema"])
        self.assertEqual("192.0.2.1", cfg["battery"]["host"])


class SettingsMappingTests(unittest.TestCase):
    def test_loopback_without_phone_access(self):
        cfg = appconfig.default_config()
        cfg["battery"]["host"] = "192.0.2.10"
        s = appconfig.to_settings(cfg, history_db="h.db")
        self.assertEqual("127.0.0.1", s.bind)
        self.assertEqual("", s.api_token)
        self.assertEqual("off", s.outage_provider)
        self.assertEqual("h.db", s.history_db)

    def test_phone_access_listens_on_lan_and_requires_token(self):
        cfg = appconfig.default_config()
        cfg["battery"]["host"] = "192.0.2.10"
        cfg["phone_access"].update(enabled=True, token="")
        cfg = appconfig.ensure_token(cfg)
        self.assertGreaterEqual(len(cfg["phone_access"]["token"]), 20)
        s = appconfig.to_settings(cfg, history_db="h.db")
        self.assertEqual("0.0.0.0", s.bind)
        self.assertEqual(cfg["phone_access"]["token"], s.api_token)

    def test_region_turns_outage_provider_on(self):
        cfg = appconfig.default_config()
        cfg["outage"].update(region="kyiv", queue="3.1")
        s = appconfig.to_settings(cfg, history_db="h.db")
        self.assertEqual("auto", s.outage_provider)
        self.assertEqual("3.1", s.outage_queue)


class FormUpdateTests(unittest.TestCase):
    def test_valid_form(self):
        cfg, errors = appconfig.apply_form(appconfig.default_config(), {
            "host": "192.0.2.10", "port": "30000", "region": "kyiv", "queue": "3.1", "backup_threshold_w": "7",
            "expected_load_w": "", "poll_seconds": "60", "alerts": {"soc_low": False}})
        self.assertEqual([], errors)
        self.assertEqual("192.0.2.10", cfg["battery"]["host"])
        self.assertEqual(60, cfg["poll_seconds"])
        self.assertIsNone(cfg["expected_load_w"])
        self.assertFalse(cfg["alerts"]["soc_low"])

    def test_invalid_form_reports_ukrainian_errors_and_keeps_config(self):
        base = appconfig.default_config()
        cfg, errors = appconfig.apply_form(base, {"host": "not-an-ip", "port": "0", "region": "atlantis",
                                                  "queue": "1.1"})
        self.assertGreaterEqual(len(errors), 3)
        self.assertTrue(any("IP" in e for e in errors))
        self.assertEqual("", cfg["battery"]["host"])

    def test_region_requires_queue(self):
        _, errors = appconfig.apply_form(appconfig.default_config(), {"host": "192.0.2.10", "region": "kyiv",
                                                                     "queue": ""})
        self.assertTrue(any("черг" in e for e in errors))


if __name__ == "__main__":
    unittest.main()

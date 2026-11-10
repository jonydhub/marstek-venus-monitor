import os
import tempfile
import unittest

from config import ConfigError, load_env_file, load_settings, parse_env_file, validate


class EnvFileTests(unittest.TestCase):
    def test_parses_comments_quotes_and_export(self):
        text = (
            "# comment\n"
            "\n"
            "MARSTEK_HOST=10.0.0.5\n"
            "export MARSTEK_PORT=30000\n"
            "MARSTEK_API_TOKEN=\"s3cr et\"\n"
            "MARSTEK_OUTAGE_REGION='kyiv'  \n"
            "BROKEN LINE\n"
        )
        self.assertEqual(parse_env_file(text), {
            "MARSTEK_HOST": "10.0.0.5",
            "MARSTEK_PORT": "30000",
            "MARSTEK_API_TOKEN": "s3cr et",
            "MARSTEK_OUTAGE_REGION": "kyiv",
        })

    def test_file_values_do_not_override_real_environment(self):
        with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as handle:
            handle.write("MARSTEK_HOST=10.0.0.5\nMARSTEK_PORT=31000\n")
        try:
            env = {"MARSTEK_HOST": "10.0.0.9"}
            self.assertTrue(load_env_file(handle.name, env))
            self.assertEqual(env, {"MARSTEK_HOST": "10.0.0.9", "MARSTEK_PORT": "31000"})
        finally:
            os.unlink(handle.name)

    def test_missing_file_is_not_an_error(self):
        env = {}
        self.assertFalse(load_env_file("/nonexistent/marstek.env", env))
        self.assertEqual(env, {})


class SettingsTests(unittest.TestCase):
    def test_neutral_defaults_have_no_personal_values(self):
        settings = load_settings({})
        self.assertEqual(settings.host, "")
        self.assertEqual(settings.port, 30000)
        self.assertEqual(settings.bind, "127.0.0.1")
        self.assertEqual(settings.http_port, 8767)
        self.assertEqual(settings.api_token, "")
        self.assertEqual(settings.outage_provider, "off")
        self.assertEqual(settings.outage_region, "")
        self.assertEqual(settings.outage_queue, "")
        self.assertEqual(settings.loe_building, "")
        self.assertIsNone(settings.loe_city_id)
        self.assertIsNone(settings.loe_street_id)
        self.assertEqual(settings.backup_threshold_w, 5.0)
        self.assertEqual(settings.rate_limit_per_minute, 120)

    def test_missing_host_is_a_clear_error(self):
        errors = validate(load_settings({}))
        self.assertTrue(any("MARSTEK_HOST" in e for e in errors))

    def test_valid_ua_outages_config(self):
        settings = load_settings({
            "MARSTEK_HOST": "10.0.0.5", "MARSTEK_OUTAGE_PROVIDER": "ua-outages",
            "MARSTEK_OUTAGE_REGION": "kyiv", "MARSTEK_OUTAGE_QUEUE": "3.1",
            "MARSTEK_BIND": "0.0.0.0", "MARSTEK_DASHBOARD_PORT": "18790",
            "MARSTEK_BACKUP_THRESHOLD_W": "12.5", "MARSTEK_API_TOKEN": "abc",
        })
        self.assertEqual(validate(settings), [])
        self.assertEqual(settings.bind, "0.0.0.0")
        self.assertEqual(settings.http_port, 18790)
        self.assertEqual(settings.backup_threshold_w, 12.5)
        self.assertEqual(settings.api_token, "abc")

    def test_ua_outages_requires_region_and_queue(self):
        errors = validate(load_settings({"MARSTEK_HOST": "h", "MARSTEK_OUTAGE_PROVIDER": "ua-outages"}))
        self.assertTrue(any("MARSTEK_OUTAGE_REGION" in e for e in errors))
        self.assertTrue(any("MARSTEK_OUTAGE_QUEUE" in e for e in errors))

    def test_region_without_provider_means_auto(self):
        settings = load_settings({"MARSTEK_HOST": "h", "MARSTEK_OUTAGE_REGION": "kyiv", "MARSTEK_OUTAGE_QUEUE": "1.1"})
        self.assertEqual(settings.outage_provider, "auto")
        self.assertEqual(validate(settings), [])

    def test_auto_requires_region_and_queue(self):
        errors = validate(load_settings({"MARSTEK_HOST": "h", "MARSTEK_OUTAGE_PROVIDER": "auto"}))
        self.assertTrue(any("MARSTEK_OUTAGE_REGION" in e for e in errors))

    def test_unknown_provider_is_rejected(self):
        errors = validate(load_settings({"MARSTEK_HOST": "h", "MARSTEK_OUTAGE_PROVIDER": "dtek"}))
        self.assertTrue(any("MARSTEK_OUTAGE_PROVIDER" in e for e in errors))

    def test_loe_accepts_legacy_group_variable(self):
        settings = load_settings({"MARSTEK_HOST": "h", "MARSTEK_OUTAGE_PROVIDER": "loe",
                                  "MARSTEK_OUTAGE_GROUP": "2.2", "MARSTEK_OUTAGE_CITY_ID": "5"})
        self.assertEqual(settings.outage_queue, "2.2")
        self.assertEqual(settings.loe_city_id, 5)
        self.assertEqual(validate(settings), [])

    def test_bad_numbers_raise_config_error(self):
        with self.assertRaises(ConfigError):
            load_settings({"MARSTEK_DASHBOARD_PORT": "abc"})


if __name__ == "__main__":
    unittest.main()

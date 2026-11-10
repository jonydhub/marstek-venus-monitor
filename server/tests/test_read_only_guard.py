import unittest
from unittest.mock import MagicMock, patch

import server
from config import load_settings
from server import METHODS, is_read_only_method, poller


class ReadOnlyGuardTests(unittest.TestCase):
    def test_every_polled_method_is_a_get(self):
        for method in METHODS:
            self.assertTrue(is_read_only_method(method), method)

    def test_control_methods_are_refused(self):
        for method in ("ES.SetMode", "Bat.SetStatus", "Wifi.Set", "ES.Setmode", "Marstek.Reboot", "", "ES.GetMode;x",
                       "ES.GetMode ", "../ES.GetMode"):
            self.assertFalse(is_read_only_method(method), method)

    def test_query_never_sends_a_control_method(self):
        fake = MagicMock()
        with patch.object(poller, "sock", fake):
            with self.assertRaises(ValueError):
                poller.query("ES.SetMode")
        fake.sendto.assert_not_called()


class RequestIdTests(unittest.TestCase):
    def test_request_ids_stay_within_uint16(self):
        # The device echoes ids as uint16; larger ids would never match the reply.
        original = poller.seq
        try:
            poller.seq = 65534
            ids = [poller.next_request_id() for _ in range(4)]
        finally:
            poller.seq = original
        self.assertEqual([65535, 1, 2, 3], ids)


class ApplySettingsTests(unittest.TestCase):
    def setUp(self):
        self.saved = (server.SETTINGS, server.POLL_SECONDS)

    def tearDown(self):
        server.apply_settings(self.saved[0], poll_seconds=self.saved[1])

    def test_apply_settings_rebinds_runtime_values(self):
        settings = load_settings({"MARSTEK_HOST": "192.0.2.10", "MARSTEK_PORT": "30001",
                                  "MARSTEK_BACKUP_THRESHOLD_W": "12", "MARSTEK_EXPECTED_LOAD_W": "150"})
        server.apply_settings(settings, poll_seconds=45)
        self.assertEqual(("192.0.2.10", 30001), (server.HOST, server.PORT))
        self.assertEqual(12.0, server.BACKUP_THRESHOLD_W)
        self.assertEqual(150.0, server.EXPECTED_LOAD_W)
        self.assertEqual(45, server.POLL_SECONDS)
        self.assertEqual("192.0.2.10", poller.snapshot()["device"]["ip"])

    def test_poll_interval_has_a_floor(self):
        server.apply_settings(load_settings({}), poll_seconds=1)
        self.assertGreaterEqual(server.POLL_SECONDS, server.MIN_POLL_SECONDS)


class HandlerHookTests(unittest.TestCase):
    def test_base_handler_never_exempts_auth(self):
        self.assertFalse(server.Handler.auth_exempt(object.__new__(server.Handler)))


if __name__ == "__main__":
    unittest.main()

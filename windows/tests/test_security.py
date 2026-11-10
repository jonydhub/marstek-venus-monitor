import logging
import unittest

from tests import _path  # noqa: F401
from mvm import logsetup, localguard, phone


class RedactionTests(unittest.TestCase):
    def test_tokens_are_redacted(self):
        f = logsetup.RedactingFilter(lambda: ["s3cr3t-token-value"])
        rec = logging.LogRecord("x", logging.INFO, "f", 1, "GET /?token=%s Authorization: Bearer abc.def %s",
                                ("s3cr3t-token-value", "s3cr3t-token-value"), None)
        f.filter(rec)
        msg = rec.getMessage()
        self.assertNotIn("s3cr3t-token-value", msg)
        self.assertNotIn("abc.def", msg)
        self.assertIn("token=***", msg)


class LocalGuardTests(unittest.TestCase):
    def test_loopback_with_matching_host(self):
        self.assertTrue(localguard.is_local_request("127.0.0.1", "127.0.0.1:8767", 8767))
        self.assertTrue(localguard.is_local_request("::1", "localhost:8767", 8767))

    def test_lan_client_or_rebinding_host_is_refused(self):
        self.assertFalse(localguard.is_local_request("192.0.2.7", "127.0.0.1:8767", 8767))
        self.assertFalse(localguard.is_local_request("127.0.0.1", "evil.example:8767", 8767))
        self.assertFalse(localguard.is_local_request("127.0.0.1", "127.0.0.1:9999", 8767))
        self.assertFalse(localguard.is_local_request("127.0.0.1", None, 8767))

    def test_setup_nonce_check_is_constant_time_and_strict(self):
        self.assertTrue(localguard.nonce_ok("abc", "abc"))
        self.assertFalse(localguard.nonce_ok("", "abc"))
        self.assertFalse(localguard.nonce_ok(None, "abc"))


class PhoneTests(unittest.TestCase):
    def test_urls_use_private_lan_ips_only(self):
        info = phone.phone_access_info(8767, "tok123", ["192.168.0.23", "100.101.102.103", "127.0.0.1", "8.8.8.8"])
        self.assertEqual(["http://192.168.0.23:8767/"], info["urls"])
        self.assertEqual("http://192.168.0.23:8767/#token=tok123", info["pair_url"])
        self.assertEqual("tok123", info["token"])

    def test_no_lan_ip(self):
        info = phone.phone_access_info(8767, "tok", [])
        self.assertEqual([], info["urls"])
        self.assertIsNone(info["pair_url"])


if __name__ == "__main__":
    unittest.main()

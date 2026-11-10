import json
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import server
from server import Handler, RateLimiter, ThreadingHTTPServer


class ServerCase(unittest.TestCase):
    def setUp(self):
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"
        self.limiter = patch.object(server, "RATE_LIMITER", RateLimiter(limit=1000, window=60))
        self.limiter.start()

    def tearDown(self):
        self.limiter.stop()
        self.httpd.shutdown()
        self.httpd.server_close()

    def get(self, path, token=None):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        try:
            with urlopen(Request(self.base + path, headers=headers), timeout=5) as response:
                return response.status, dict(response.headers), response.read()
        except HTTPError as error:
            return error.code, dict(error.headers), error.read()


class TokenAuthTests(ServerCase):
    def test_api_open_without_configured_token(self):
        with patch.object(server, "API_TOKEN", ""):
            self.assertEqual(self.get("/api/status")[0], 200)

    def test_api_requires_bearer_token_when_configured(self):
        with patch.object(server, "API_TOKEN", "s3cret"):
            status, headers, body = self.get("/api/status")
            self.assertEqual(status, 401)
            self.assertIn("Bearer", headers.get("WWW-Authenticate", ""))
            self.assertEqual(json.loads(body)["error"], "unauthorized")
            self.assertEqual(self.get("/api/status", token="wrong")[0], 401)
            self.assertEqual(self.get("/api/status", token="s3cret")[0], 200)

    def test_token_in_query_string_is_not_accepted_by_api(self):
        with patch.object(server, "API_TOKEN", "s3cret"):
            self.assertEqual(self.get("/api/status?token=s3cret")[0], 401)

    def test_static_dashboard_stays_public_so_it_can_prompt_for_token(self):
        with patch.object(server, "API_TOKEN", "s3cret"):
            status, _, body = self.get("/")
            self.assertEqual(status, 200)
            self.assertTrue(b'id="token-form"' in body, "token form missing from index.html")

    def test_compare_is_constant_time(self):
        source = open(server.__file__, encoding="utf-8").read()
        self.assertIn("hmac.compare_digest", source)


class HealthTests(ServerCase):
    def test_health_is_minimal_and_does_not_leak_device_address(self):
        with patch.object(server, "API_TOKEN", "s3cret"):
            status, _, body = self.get("/health")
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertTrue(payload["ok"])
        self.assertLessEqual(set(payload), {"ok", "device_responding", "last_poll_age_seconds"})
        self.assertNotIn(b"udp", body)
        self.assertNotIn(server.HOST.encode() or b"\x00", body)


class RateLimitTests(ServerCase):
    def test_limiter_window(self):
        clock = [0.0]
        limiter = RateLimiter(limit=2, window=60, clock=lambda: clock[0])
        self.assertTrue(limiter.allow("a")[0])
        self.assertTrue(limiter.allow("a")[0])
        allowed, retry = limiter.allow("a")
        self.assertFalse(allowed)
        self.assertGreater(retry, 0)
        self.assertTrue(limiter.allow("b")[0])
        clock[0] = 61
        self.assertTrue(limiter.allow("a")[0])

    def test_api_returns_429_when_limit_exceeded(self):
        with patch.object(server, "RATE_LIMITER", RateLimiter(limit=2, window=60)), \
                patch.object(server, "API_TOKEN", ""):
            self.assertEqual(self.get("/api/status")[0], 200)
            self.assertEqual(self.get("/api/status")[0], 200)
            status, headers, _ = self.get("/api/status")
            self.assertEqual(status, 429)
            self.assertIn("Retry-After", headers)
            self.assertEqual(self.get("/")[0], 200)  # static files are not limited



class DefaultLimitTests(unittest.TestCase):
    def test_default_limit_is_120_per_minute(self):
        self.assertEqual(server.RATE_LIMITER.limit, 120)


class RegionsEndpointTests(ServerCase):
    def test_regions_catalog(self):
        catalog = [{"id": "kyiv", "name_ua": "Київ", "queues": ["1.1", "2.1"]}]
        with patch.object(server, "regions_catalog", return_value=catalog), patch.object(server, "API_TOKEN", "t"):
            self.assertEqual(self.get("/api/regions")[0], 401)
            status, _, body = self.get("/api/regions", token="t")
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertEqual(payload["regions"], catalog)
        self.assertIn("source", payload)

    def test_regions_upstream_failure_is_502(self):
        with patch.object(server, "regions_catalog", side_effect=OSError("down")), patch.object(server, "API_TOKEN", ""):
            self.assertEqual(self.get("/api/regions")[0], 502)


class ConfigWiringTests(unittest.TestCase):
    def test_no_personal_defaults_in_server_module(self):
        source = open(server.__file__, encoding="utf-8").read()
        # markers are assembled so this file itself stays free of the old personal values
        for marker in ("TAILNET", "100." + "109.", "192.168.1." + "150", "127" + "83"):
            self.assertNotIn(marker, source)


if __name__ == "__main__":
    unittest.main()

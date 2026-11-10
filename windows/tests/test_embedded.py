import http.client
import json
import socket
import tempfile
import unittest
from pathlib import Path

from tests import _path  # noqa: F401
from mvm import appconfig
from mvm.controller import Controller
from mvm.paths import AppPaths


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class EmbeddedServerTests(unittest.TestCase):
    """The real server module, embedded, plus the /setup routes that only this PC may use."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = Path(cls.tmp.name)
        cls.paths = AppPaths(config_file=root / "cfg" / "config.json", data_dir=root / "data",
                             history_db=root / "data" / "history.db", log_dir=root / "logs",
                             webview_dir=root / "wv")
        cfg = appconfig.default_config()
        cfg["dashboard_port"] = free_port()
        appconfig.save_config(cls.paths.config_file, cfg)
        cls.discovered = []
        cls.ctl = Controller(cls.paths, discover=lambda port: cls.discovered or [{"ip": "192.0.2.10", "port": port,
                                                                                  "model": "VenusE", "firmware": 1}],
                             probe=lambda host, port: {"ok": True, "ip": host, "port": port, "soc": 55},
                             local_ips=lambda: ["192.168.0.23"])
        cls.ctl.start(threads=False)
        cls.port = cls.ctl.server.port

    @classmethod
    def tearDownClass(cls):
        cls.ctl.stop()
        cls.tmp.cleanup()

    def request(self, method, path, body=None, headers=None, host=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        hdrs = {"Host": host or f"127.0.0.1:{self.port}"}
        hdrs.update(headers or {})
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            hdrs["Content-Type"] = "application/json"
        conn.request(method, path, body=data, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, raw

    def post(self, action, body, nonce=True, host=None):
        headers = {"X-MVM-Setup": self.ctl.nonce} if nonce else {}
        status, raw = self.request("POST", f"/api/setup/{action}", body, headers, host)
        return status, json.loads(raw) if raw else None

    def test_dashboard_and_status_are_served(self):
        status, raw = self.request("GET", "/")
        self.assertEqual(200, status)
        self.assertIn(b"<html", raw.lower())
        status, raw = self.request("GET", "/api/status")
        self.assertEqual(200, status)
        self.assertIn("methods", json.loads(raw))

    def test_setup_page_has_nonce(self):
        status, raw = self.request("GET", "/setup")
        self.assertEqual(200, status)
        self.assertIn(self.ctl.nonce.encode(), raw)
        self.assertIn("Знайти батарею".encode(), raw)

    def test_setup_refuses_foreign_host_header(self):
        status, _ = self.request("GET", "/setup", host="attacker.example")
        self.assertEqual(403, status)
        status, _ = self.post("save", {"host": "192.0.2.10"}, host="attacker.example")
        self.assertEqual(403, status)

    def test_post_without_nonce_is_refused(self):
        status, _ = self.post("save", {"host": "192.0.2.10"}, nonce=False)
        self.assertEqual(403, status)

    def test_save_applies_and_persists(self):
        status, state = self.post("save", {"host": "192.0.2.10", "port": 30000, "region": "kyiv", "queue": "3.1",
                                           "poll_seconds": 45})
        self.assertEqual(200, status, state)
        self.assertTrue(state["configured"])
        saved = json.loads(self.paths.config_file.read_text("utf-8"))
        self.assertEqual("192.0.2.10", saved["battery"]["host"])
        import server
        self.assertEqual("192.0.2.10", server.HOST)
        self.assertEqual(45, server.POLL_SECONDS)
        self.assertEqual("auto", server.SETTINGS.outage_provider)

    def test_invalid_save_returns_errors(self):
        status, payload = self.post("save", {"host": "999.1.1.1"})
        self.assertEqual(400, status)
        self.assertTrue(payload["errors"])

    def test_discover_and_test_actions(self):
        status, payload = self.post("discover", {})
        self.assertEqual(200, status)
        self.assertEqual("192.0.2.10", payload["devices"][0]["ip"])
        status, payload = self.post("test", {"host": "192.0.2.10", "port": 30000})
        self.assertEqual(200, status)
        self.assertEqual(55, payload["soc"])
        status, _ = self.post("test", {"host": "nope"})
        self.assertEqual(400, status)

    def test_phone_access_rebinds_and_keeps_local_window_tokenless(self):
        status, state = self.post("phone", {"enabled": True})
        self.assertEqual(200, status)
        try:
            self.assertEqual("0.0.0.0", self.ctl.server.bound[0])
            self.assertTrue(state["phone"]["token"])
            self.assertEqual(f"http://192.168.0.23:{self.port}/", state["phone"]["urls"][0])
            import server
            self.assertEqual(state["phone"]["token"], server.API_TOKEN)
            # loopback desktop window: no token needed
            status, _ = self.request("GET", "/api/status")
            self.assertEqual(200, status)
            # same request with a non-local Host header must present the token
            status, _ = self.request("GET", "/api/status", host=f"192.168.0.23:{self.port}")
            self.assertEqual(401, status)
        finally:
            status, state = self.post("phone", {"enabled": False})
        self.assertEqual("127.0.0.1", self.ctl.server.bound[0])
        self.assertEqual("", state["phone"]["token"])

    def test_control_style_post_to_server_routes_stays_refused(self):
        status, _ = self.request("POST", "/api/status", {})
        self.assertEqual(405, status)


if __name__ == "__main__":
    unittest.main()

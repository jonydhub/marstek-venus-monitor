import json
import os
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import urlopen

import server
from history import HistoryStore
from server import Handler, RateLimiter, ThreadingHTTPServer, poller


def responder(offgrid=0, ok=True, soc=80):
    def response(method):
        if not ok:
            return {"ok": False, "error": "No response after retry"}
        if method == "Bat.GetStatus":
            return {"ok": True, "data": {"soc": soc, "bat_temp": 24.0, "bat_capacity": soc * 51.2, "rated_capacity": 5120.0}}
        if method == "ES.GetStatus":
            return {"ok": True, "data": {"offgrid_power": offgrid, "ongrid_power": 0}}
        return {"ok": True, "data": {}}
    return response


class PollerJournalTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = HistoryStore(os.path.join(self.tmp.name, "h.db"))
        self.patches = [patch.object(poller, "history", self.store), patch("server.time.sleep")]
        for p in self.patches:
            p.start()
        poller.detector = None

    def tearDown(self):
        for p in self.patches:
            p.stop()
        self.store.close()
        self.tmp.cleanup()

    def poll(self, **kwargs):
        with patch.object(poller, "query", side_effect=responder(**kwargs)):
            poller.poll_once()

    def test_lost_connection_is_unknown_light_and_a_connection_event_only(self):
        self.poll()
        for _ in range(3):
            self.poll(ok=False)
        snap = poller.snapshot()
        self.assertEqual(snap["power_outage"]["grid_state"], "unknown")
        self.assertTrue(snap["connection"]["lost"])
        self.assertEqual([e["kind"] for e in self.store.events()], ["connection_lost"])

    def test_backup_output_draw_is_journaled_as_light_lost(self):
        self.poll()
        self.poll(offgrid=121)
        snap = poller.snapshot()
        self.assertEqual(snap["power_outage"]["grid_state"], "off")
        self.assertEqual(snap["power_outage"]["autonomy"]["load_basis"], "measured_backup_output")
        self.assertEqual(snap["power_outage"]["autonomy"]["floor_pct"], 11)
        self.assertEqual([e["kind"] for e in self.store.events()], ["light_lost"])
        self.assertEqual([e["kind"] for e in snap["insights"]["chart_events"]], ["light_lost"])


class EventsEndpointTests(unittest.TestCase):
    def test_events_endpoint_returns_newest_first_with_category_filter(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = HistoryStore(os.path.join(tmp, "h.db"))
            store.record_event({"ts": 1_800_000_000.0, "kind": "light_lost", "category": "light", "text": "Світло зникло",
                                "value": 121.0})
            store.record_event({"ts": 1_800_000_100.0, "kind": "connection_lost", "category": "connection",
                                "text": "Немає зв'язку", "value": None})
            httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            threading.Thread(target=httpd.serve_forever, daemon=True).start()
            try:
                with patch.object(poller, "history", store), patch.object(server, "API_TOKEN", ""), \
                        patch.object(server, "RATE_LIMITER", RateLimiter(limit=1000, window=60)):
                    base = f"http://127.0.0.1:{httpd.server_port}"
                    with urlopen(base + "/api/events?limit=200", timeout=5) as r:
                        body = json.loads(r.read())
                    with urlopen(base + "/api/events?category=light", timeout=5) as r:
                        light = json.loads(r.read())
            finally:
                httpd.shutdown()
                httpd.server_close()
                store.close()
        self.assertEqual([e["kind"] for e in body["events"]], ["connection_lost", "light_lost"])
        self.assertTrue(body["events"][0]["ts"].endswith("+02:00") or body["events"][0]["ts"].endswith("+03:00"))
        self.assertEqual([e["kind"] for e in light["events"]], ["light_lost"])


if __name__ == "__main__":
    unittest.main()

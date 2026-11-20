"""week_light(): the measured light timeline («Фактично») for today and the 6 previous Kyiv days; GET /api/light-week."""
import json
import os
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import urlopen

import server
from history import HistoryStore, day_starts, week_light
from server import Handler, RateLimiter, ThreadingHTTPServer, poller
from tests.test_history import ts
from tests.test_history_today import drain, states


class DayStartTests(unittest.TestCase):
    def test_seven_kyiv_midnights_newest_first(self):
        starts = day_starts(ts("2026-11-20T12:00:00"))
        self.assertEqual(len(starts), 7)
        self.assertEqual(starts[0], ts("2026-11-20T00:00:00"))
        self.assertEqual(starts[-1], ts("2026-11-14T00:00:00"))

    def test_dst_day_is_25_hours(self):  # 2026-10-25: clocks go back in Kyiv
        starts = day_starts(ts("2026-10-26T12:00:00"))
        self.assertEqual(starts[0] - starts[1], 25 * 3600)


class WeekLightTests(unittest.TestCase):
    def setUp(self):
        self.now = ts("2026-11-20T12:00:00")

    def test_outage_across_midnight_is_split_between_the_two_days(self):
        rows = (states(ts("2026-11-19T20:00:00"), 3 * 3600, 30, offgrid=0.0)        # 20:00–23:00 light
                + drain(ts("2026-11-19T23:00:00"), 2 * 3600, 30, offgrid=150.0)   # 23:00–01:00 no light
                + states(ts("2026-11-20T01:00:00"), 11 * 3600, 30, offgrid=0.0))  # light again
        days = week_light(rows, now=self.now)
        today, yesterday = days[0], days[1]
        self.assertEqual([d["date"] for d in days[:2]], ["2026-11-20", "2026-11-19"])
        self.assertTrue(today["is_today"])
        self.assertAlmostEqual(yesterday["off_seconds"], 3600, delta=60)
        self.assertAlmostEqual(today["off_seconds"], 3600, delta=60)
        self.assertGreater(today["on_seconds"], 10 * 3600)
        self.assertAlmostEqual(yesterday["unknown_seconds"], 20 * 3600 + 300, delta=60)  # no data before 20:00, light confirmed after 5 min idle
        self.assertEqual(today["end"], "2026-11-21T00:00:00+02:00")
        self.assertEqual(today["timeline"][-1]["end"], "2026-11-20T12:00:00+02:00")

    def test_days_without_samples_are_all_unknown(self):
        days = week_light([], now=self.now)
        self.assertEqual(len(days), 7)
        self.assertTrue(all(d["off_seconds"] == 0 and d["on_seconds"] == 0 for d in days))
        self.assertEqual(days[3]["unknown_seconds"], 24 * 3600)
        self.assertEqual(days[0]["unknown_seconds"], 12 * 3600)  # today only up to now


class LightWeekEndpointTests(unittest.TestCase):
    def test_endpoint_serves_seven_days(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = HistoryStore(os.path.join(tmp, "h.db"))
            for r in states(ts("2026-11-20T08:00:00"), 3600, 30, offgrid=0.0):
                store.record(r)
            httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            threading.Thread(target=httpd.serve_forever, daemon=True).start()
            try:
                with patch.object(poller, "history", store), patch.object(server, "API_TOKEN", ""), \
                        patch.object(server, "RATE_LIMITER", RateLimiter(limit=1000, window=60)), \
                        patch.object(server, "_light_week_cache", {"at": 0.0, "payload": None}), \
                        patch("server.time.time", return_value=ts("2026-11-20T09:00:00")):
                    with urlopen(f"http://127.0.0.1:{httpd.server_port}/api/light-week", timeout=5) as r:
                        body = json.loads(r.read())
            finally:
                httpd.shutdown()
                httpd.server_close()
                store.close()
        self.assertEqual(len(body["days"]), 7)
        self.assertEqual(body["days"][0]["date"], "2026-11-20")
        self.assertGreater(body["days"][0]["on_seconds"], 3000)


if __name__ == "__main__":
    unittest.main()

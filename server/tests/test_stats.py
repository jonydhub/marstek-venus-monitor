"""outage_details / charge_sessions / stats in history.py and GET /api/stats («Статистика»)."""
import json
import os
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import urlopen

import server
from history import HistoryStore, charge_sessions, outage_details, stats, today_durations
from server import Handler, RateLimiter, ThreadingHTTPServer, poller
from tests.test_history import row, ts
from tests.test_history_today import drain, states


def charging(start, seconds, step, watts, soc_from, soc_to):
    n = max(1, seconds // step)
    return [row(start + i * step, offgrid=0.0, ongrid_w=-watts, flow_dir="charging", flow_w=watts,
                soc=round(soc_from + (soc_to - soc_from) * i / n)) for i in range(n)]


class OutageDetailTests(unittest.TestCase):
    def test_outage_has_energy_average_and_soc(self):
        rows = (states(ts("2026-11-21T15:00:00"), 3600, 15, offgrid=0.0, soc=99)
                + [dict(r, soc=99 - i // 40) for i, r in enumerate(drain(ts("2026-11-21T16:00:00"), 1800, 15, offgrid=600.0))]
                + states(ts("2026-11-21T16:30:00"), 1800, 15, offgrid=0.0, soc=92))
        out = outage_details(rows, start=ts("2026-11-21T00:00:00"), now=ts("2026-11-21T17:00:00"))
        self.assertEqual(len(out), 1)
        o = out[0]
        self.assertTrue(o["start"].startswith("2026-11-21T16:00"))
        self.assertAlmostEqual(o["seconds"], 1800, delta=30)
        self.assertAlmostEqual(o["energy_wh"], 300.0, delta=5)   # 600 W × 0.5 h
        self.assertAlmostEqual(o["avg_w"], 600, delta=10)
        self.assertEqual(o["soc_start"], 99)
        self.assertFalse(o["ongoing"])

    def test_today_block_lists_outages_with_energy(self):
        rows = drain(ts("2026-11-21T01:00:00"), 3600, 30, offgrid=200.0)
        today = today_durations(rows, now=ts("2026-11-21T01:30:00"))
        self.assertEqual(len(today["outages"]), 1)
        self.assertTrue(today["outages"][0]["ongoing"])
        self.assertAlmostEqual(today["outage_energy_wh"], 100.0, delta=5)  # 200 W × 0.5 h so far


class ChargeSessionTests(unittest.TestCase):
    def test_recharge_is_one_session_with_energy_and_soc(self):
        rows = charging(ts("2026-11-21T16:35:00"), 1560, 15, 1100.0, 92, 100) + \
            states(ts("2026-11-21T17:01:00"), 600, 15, offgrid=0.0, soc=100)
        out = charge_sessions(rows, start=ts("2026-11-21T00:00:00"), now=ts("2026-11-21T17:20:00"))
        self.assertEqual(len(out), 1)
        c = out[0]
        self.assertAlmostEqual(c["energy_wh"], 1100 * 1560 / 3600, delta=10)
        self.assertEqual((c["soc_start"], c["soc_end"]), (92, 100))
        self.assertFalse(c["estimated"])

    def test_standby_topup_is_left_out(self):
        rows = charging(ts("2026-11-21T10:00:00"), 360, 15, 300.0, 99, 100)  # 30 Wh, +1 %
        self.assertEqual(charge_sessions(rows, start=ts("2026-11-21T00:00:00"), now=ts("2026-11-21T11:00:00")), [])

    def test_runs_with_a_short_pause_merge(self):
        rows = (charging(ts("2026-11-21T10:00:00"), 600, 15, 1000.0, 80, 85)
                + states(ts("2026-11-21T10:10:00"), 120, 15, offgrid=0.0, soc=85)
                + charging(ts("2026-11-21T10:12:00"), 600, 15, 1000.0, 85, 90))
        out = charge_sessions(rows, start=ts("2026-11-21T00:00:00"), now=ts("2026-11-21T11:00:00"))
        self.assertEqual(len(out), 1)
        self.assertEqual((out[0]["soc_start"], out[0]["soc_end"]), (80, 90))


class StatsTests(unittest.TestCase):
    def test_days_outages_and_charges_newest_first(self):
        rows = (drain(ts("2026-11-20T10:00:00"), 3600, 30, offgrid=150.0)
                + charging(ts("2026-11-20T11:00:00"), 1800, 30, 1000.0, 90, 100)
                + drain(ts("2026-11-21T08:00:00"), 1800, 30, offgrid=300.0)
                + states(ts("2026-11-21T08:30:00"), 1800, 30, offgrid=0.0))
        st = stats(rows, now=ts("2026-11-21T12:00:00"))
        self.assertEqual([d["date"] for d in st["days"][:2]], ["2026-11-21", "2026-11-20"])
        self.assertEqual(st["days"][1]["outages"], 1)
        self.assertAlmostEqual(st["days"][1]["outage_energy_wh"], 150.0, delta=5)
        self.assertAlmostEqual(st["days"][0]["outage_energy_wh"], 150.0, delta=5)
        self.assertGreater(st["days"][1]["charge_wh"], 400)
        self.assertTrue(st["outages"][0]["start"].startswith("2026-11-21T08"))
        self.assertEqual(len(st["charges"]), 1)


class StatsEndpointTests(unittest.TestCase):
    def test_endpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = HistoryStore(os.path.join(tmp, "h.db"))
            for r in drain(ts("2026-11-21T08:00:00"), 1800, 30, offgrid=300.0):
                store.record(r)
            httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            threading.Thread(target=httpd.serve_forever, daemon=True).start()
            try:
                with patch.object(poller, "history", store), patch.object(server, "API_TOKEN", ""), \
                        patch.object(server, "RATE_LIMITER", RateLimiter(limit=1000, window=60)), \
                        patch.object(server, "_stats_cache", {"at": 0.0, "payload": None}), \
                        patch("server.time.time", return_value=ts("2026-11-21T09:00:00")):
                    with urlopen(f"http://127.0.0.1:{httpd.server_port}/api/stats", timeout=5) as r:
                        body = json.loads(r.read())
            finally:
                httpd.shutdown()
                httpd.server_close()
                store.close()
        self.assertEqual(len(body["days"]), 7)
        self.assertEqual(len(body["outages"]), 1)
        self.assertIn("charges", body)


if __name__ == "__main__":
    unittest.main()

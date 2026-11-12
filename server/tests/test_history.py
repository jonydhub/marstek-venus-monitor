import os
import sqlite3
import tempfile
import unittest
from datetime import datetime
from unittest.mock import patch
from zoneinfo import ZoneInfo

from history import HistoryStore, compute_insights, measured_backup_load

KYIV = ZoneInfo("Europe/Kyiv")


def ts(text):
    return datetime.fromisoformat(text).replace(tzinfo=KYIV).timestamp()


def row(t, offgrid=None, soc=50, **extra):
    base = {"ts": t, "soc": soc, "bat_wh": 2560.0, "flow_w": 0.0, "flow_dir": "idle",
            "offgrid_w": offgrid, "ongrid_w": 0.0, "temp_c": 25.0}
    base.update(extra)
    return base


def series(start, seconds, step, offgrid, soc=50):
    return [row(start + i, offgrid, soc) for i in range(0, seconds + 1, step)]


class HistoryStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "nested", "history.db")

    def tearDown(self):
        self.tmp.cleanup()

    def test_creates_directory_and_survives_reopen(self):
        store = HistoryStore(self.path)
        store.record(row(1000.0, offgrid=12.5, soc=77))
        store.close()
        reopened = HistoryStore(self.path)
        rows = reopened.since(0)
        reopened.close()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["soc"], 77)
        self.assertEqual(rows[0]["offgrid_w"], 12.5)
        self.assertEqual(rows[0]["temp_c"], 25.0)

    def test_prunes_samples_older_than_retention(self):
        store = HistoryStore(self.path, retention_seconds=48 * 3600)
        now = 1_000_000.0
        store.record(row(now - 49 * 3600))
        store.record(row(now - 47 * 3600))
        store.prune(now)
        self.assertEqual([r["ts"] for r in store.since(0)], [now - 47 * 3600])
        store.close()

    def test_record_prunes_periodically_without_explicit_call(self):
        store = HistoryStore(self.path, retention_seconds=100, prune_every=2)
        store.record(row(10.0))
        store.record(row(500.0))  # second insert triggers prune relative to its timestamp
        self.assertEqual([r["ts"] for r in store.since(0)], [500.0])
        store.close()

    def test_uses_wal_for_cheap_writes(self):
        store = HistoryStore(self.path)
        store.close()
        conn = sqlite3.connect(self.path)  # closed explicitly: Windows cannot delete an open database
        try:
            self.assertEqual(conn.execute("PRAGMA journal_mode").fetchone()[0], "wal")
        finally:
            conn.close()


class InsightsTests(unittest.TestCase):
    now = ts("2026-11-09T18:00:00")

    def test_empty_history_reports_unknowns_but_current_values(self):
        insights = compute_insights([], now=self.now, current_offgrid_w=0.0, temperature_c=26.0)
        self.assertEqual(insights, {
            "backup_output_w": 0.0,
            "backup_avg_w_24h": None,
            "backup_avg_coverage_hours": 0.0,
            "backup_energy_today_wh": None,
            "temperature_c": 26.0,
            "soc_history": [],
            "today": {"charge_seconds": 0, "discharge_seconds": 0, "outage_seconds": 0, "charge_wh": 0.0,
                      "discharge_wh": 0.0, "charge_estimated": False, "discharge_estimated": False,
                      "grid_timeline": [{"start": "2026-11-09T00:00:00+02:00",
                                         "end": insights["today"]["grid_timeline"][0]["end"], "state": "unknown"}]},
            "outage_periods": [],
            "chart_events": [],
            "standby_w": None,
            "last_grid_charge": None,
            "backup_load_mean_w_7d": None,
            "backup_load_coverage_seconds_7d": 0,
        })

    def test_time_weighted_average_needs_one_hour_coverage(self):
        rows = series(self.now - 1800, 1800, 30, 40.0)
        insights = compute_insights(rows, now=self.now, current_offgrid_w=40.0, temperature_c=None)
        self.assertIsNone(insights["backup_avg_w_24h"])
        self.assertAlmostEqual(insights["backup_avg_coverage_hours"], 0.5, places=2)

    def test_time_weighted_average_over_covered_time(self):
        # 2 h at 0 W then 2 h at 60 W -> mean ~30 W over ~4 h
        start = self.now - 4 * 3600
        rows = series(start, 7200, 30, 0.0) + series(start + 7230, 7170, 30, 60.0)
        insights = compute_insights(rows, now=self.now, current_offgrid_w=60.0, temperature_c=None)
        self.assertAlmostEqual(insights["backup_avg_w_24h"], 30.0, delta=0.5)
        self.assertAlmostEqual(insights["backup_avg_coverage_hours"], 4.0, delta=0.02)

    def test_gaps_are_not_counted_as_coverage(self):
        start = self.now - 4 * 3600
        rows = series(start, 3600, 60, 20.0) + series(start + 3 * 3600, 3600, 60, 20.0)
        insights = compute_insights(rows, now=self.now, current_offgrid_w=20.0, temperature_c=None)
        self.assertAlmostEqual(insights["backup_avg_coverage_hours"], 2.0, delta=0.02)
        self.assertAlmostEqual(insights["backup_avg_w_24h"], 20.0, delta=0.01)

    def test_samples_older_than_24h_are_ignored(self):
        rows = series(self.now - 30 * 3600, 3 * 3600, 60, 100.0) + series(self.now - 2 * 3600, 7200, 60, 10.0)
        insights = compute_insights(rows, now=self.now, current_offgrid_w=10.0, temperature_c=None)
        self.assertAlmostEqual(insights["backup_avg_w_24h"], 10.0, delta=0.01)

    def test_energy_today_integrates_since_local_midnight(self):
        midnight = ts("2026-11-09T00:00:00")
        # interval straddling midnight is excluded; only today's 2 h at 50 W count
        rows = series(midnight - 3600, 3540, 60, 500.0) + series(midnight, 2 * 3600, 60, 50.0)
        insights = compute_insights(rows, now=self.now, current_offgrid_w=0.0, temperature_c=None)
        self.assertAlmostEqual(insights["backup_energy_today_wh"], 100.0, delta=0.5)

    def test_energy_today_unknown_without_samples_today(self):
        rows = series(ts("2026-11-08T20:00:00"), 3600, 60, 50.0)
        insights = compute_insights(rows, now=self.now, current_offgrid_w=0.0, temperature_c=None)
        self.assertIsNone(insights["backup_energy_today_wh"])

    def test_soc_history_uses_last_sample_per_30_minute_bucket(self):
        start = ts("2026-11-09T16:00:00")
        rows = [row(start + 60, soc=90), row(start + 1700, soc=88), row(start + 1900, soc=85), row(start + 3500, soc=80)]
        history = compute_insights(rows, now=self.now, current_offgrid_w=0.0, temperature_c=None)["soc_history"]
        self.assertEqual(history, [
            {"t": "2026-11-09T16:28:20+02:00", "soc": 88},
            {"t": "2026-11-09T16:58:20+02:00", "soc": 80},
        ])

    def test_soc_history_is_capped_at_48_points_within_24h(self):
        rows = series(self.now - 26 * 3600, 26 * 3600, 300, 0.0, soc=60)
        history = compute_insights(rows, now=self.now, current_offgrid_w=0.0, temperature_c=None)["soc_history"]
        self.assertLessEqual(len(history), 48)
        self.assertGreaterEqual(len(history), 47)
        self.assertTrue(all(isinstance(p["soc"], int) for p in history))
        first = datetime.fromisoformat(history[0]["t"]).timestamp()
        self.assertGreaterEqual(first, self.now - 24 * 3600)

    def test_soc_history_skips_missing_soc(self):
        rows = [row(self.now - 600, soc=None)]
        history = compute_insights(rows, now=self.now, current_offgrid_w=0.0, temperature_c=None)["soc_history"]
        self.assertEqual(history, [])


class MeasuredLoadTests(unittest.TestCase):
    now = ts("2026-11-09T18:00:00")

    def test_no_measured_load_while_backup_output_idle(self):
        rows = series(self.now - 6 * 3600, 6 * 3600, 30, 0.0)
        self.assertIsNone(measured_backup_load(rows, now=self.now))

    def test_measured_load_averages_only_intervals_with_backup_draw(self):
        start = self.now - 5 * 3600
        rows = series(start, 3 * 3600, 30, 0.0) + series(start + 3 * 3600 + 30, 3600, 30, 45.0)
        self.assertAlmostEqual(measured_backup_load(rows, now=self.now), 45.0, delta=0.01)

    def test_measured_load_needs_thirty_minutes_of_draw(self):
        rows = series(self.now - 900, 900, 30, 45.0)
        self.assertIsNone(measured_backup_load(rows, now=self.now))


class PollerInsightsTests(unittest.TestCase):
    def test_status_publishes_insights_contract(self):
        from server import poller

        def response(method):
            if method == "Bat.GetStatus":
                return {"ok": True, "data": {"soc": 80, "bat_temp": 24.0, "bat_capacity": 4096.0, "rated_capacity": 5120.0}}
            if method == "ES.GetStatus":
                return {"ok": True, "data": {"offgrid_power": 0, "ongrid_power": -12}}
            return {"ok": True, "data": {}}

        with tempfile.TemporaryDirectory() as tmp:
            store = HistoryStore(os.path.join(tmp, "h.db"))
            with patch.object(poller, "query", side_effect=response), \
                    patch.object(poller, "history", store), patch("server.time.sleep"):
                poller.poll_once()
            rows = store.since(0)
            store.close()
        insights = poller.snapshot()["insights"]
        self.assertEqual(set(insights), {"backup_output_w", "backup_avg_w_24h", "backup_avg_coverage_hours",
                                         "backup_energy_today_wh", "temperature_c", "soc_history",
                                         "today", "outage_periods", "chart_events", "standby_w",
                                         "last_grid_charge", "backup_load_mean_w_7d",
                                         "backup_load_coverage_seconds_7d"})
        self.assertEqual(insights["backup_output_w"], 0.0)
        self.assertEqual(insights["temperature_c"], 24.0)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["soc"], 80)
        self.assertEqual(rows[0]["ongrid_w"], -12.0)
        self.assertEqual(rows[0]["bat_wh"], 4096.0)


if __name__ == "__main__":
    unittest.main()

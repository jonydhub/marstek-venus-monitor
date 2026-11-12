"""1.3.7: smoothed runtime load, 7-day high-load statistics, today's energy, poll cadence."""
import unittest

import server
from history import backup_load_stats, smoothed_backup_load, today_durations
from outage import _autonomy
from tests.test_history import row, ts


def series(start, seconds, step, **fields):
    return [row(start + i, **fields) for i in range(0, seconds, step)]


class SmoothedLoadTests(unittest.TestCase):
    def test_energy_weighted_mean_over_the_last_ten_minutes_of_the_outage(self):
        start = ts("2026-11-10T20:00:00")
        rows = series(start, 1200, 15, offgrid=800.0)  # 20 min at 800 W …
        rows += series(start + 1200, 300, 15, offgrid=0.0)  # … then a 5-min compressor pause
        now = rows[-1]["ts"]
        mean = smoothed_backup_load(rows, now=now, since=start)
        self.assertAlmostEqual(mean, 800 * 300 / 600 + 800 * 15 / 2 / 600, delta=15)  # ≈ half: 5 of 10 min drawing

    def test_window_starts_at_the_outage(self):
        start = ts("2026-11-10T20:00:00")
        rows = series(start - 600, 600, 15, offgrid=0.0) + series(start, 120, 15, offgrid=300.0)
        self.assertAlmostEqual(300.0, smoothed_backup_load(rows, now=rows[-1]["ts"], since=start))

    def test_needs_two_samples(self):
        start = ts("2026-11-10T20:00:00")
        self.assertIsNone(smoothed_backup_load([row(start, offgrid=300.0)], now=start, since=start))
        self.assertIsNone(smoothed_backup_load([], now=start, since=None))

    def test_autonomy_prefers_the_smoothed_load_and_falls_back_to_the_current_reading(self):
        a = _autonomy("off", 5000, {}, None, None, backup_output_w=0.0, smoothed_load_w=400.0)
        self.assertEqual(("measured_backup_avg_10min", 400.0, 45000), (a["load_basis"], a["load_w"], a["runtime_seconds"]))
        b = _autonomy("off", 5000, {}, None, None, backup_output_w=500.0, smoothed_load_w=None)
        self.assertEqual("measured_backup_output", b["load_basis"])
        c = _autonomy("on", 5000, {}, None, None, backup_output_w=0.0, smoothed_load_w=400.0)
        self.assertIsNone(c["load_basis"])


class LoadStatsTests(unittest.TestCase):
    def test_seven_day_mean_excludes_zero_load_and_older_samples(self):
        now = ts("2026-11-10T20:00:00")
        rows = series(now - 8 * 86400, 3600, 30, offgrid=2000.0)  # older than 7 days: ignored
        rows += series(now - 3 * 86400, 3600, 30, offgrid=300.0)
        rows += series(now - 3 * 86400 + 3600, 3600, 30, offgrid=0.0)  # zero load: excluded
        rows += series(now - 86400, 1800, 30, offgrid=600.0)
        mean, covered = backup_load_stats(rows, now=now)
        self.assertAlmostEqual(400.0, mean, delta=5)  # (300 × 1 h + 600 × 0.5 h) / 1.5 h
        self.assertAlmostEqual(5400 - 60, covered, delta=60)

    def test_no_draw_no_mean(self):
        now = ts("2026-11-10T20:00:00")
        self.assertEqual((None, 0.0), backup_load_stats(series(now - 3600, 3600, 30, offgrid=0.0), now=now))


class TodayEnergyTests(unittest.TestCase):
    def test_measured_charge_and_discharge_in_wh(self):
        now = ts("2026-11-10T12:00:00")
        rows = series(ts("2026-11-10T01:00:00"), 2400, 30, flow_dir="charging", ongrid_w=-1215.0, offgrid=0.0)
        rows += series(ts("2026-11-10T01:40:00"), 1800, 30, flow_dir="discharging", ongrid_w=0.0, offgrid=300.0)
        rows += [row(ts("2026-11-10T02:10:00"), flow_dir="idle", ongrid_w=0.0, offgrid=0.0)]
        today = today_durations(rows, now=now)
        self.assertAlmostEqual(810.0, today["charge_wh"], delta=1)  # 1215 W × 40 min = 0,81 кВт·год
        self.assertAlmostEqual(150.0, today["discharge_wh"], delta=1)
        self.assertFalse(today["charge_estimated"])
        self.assertFalse(today["discharge_estimated"])

    def test_capacity_trend_estimate_is_marked(self):
        now = ts("2026-11-10T12:00:00")
        rows = series(ts("2026-11-10T01:00:00"), 1800, 30, flow_dir="discharging", flow_w=-200.0, ongrid_w=0.0, offgrid=0.0)
        rows += [row(ts("2026-11-10T01:30:00"), flow_dir="idle", ongrid_w=0.0, offgrid=0.0)]
        today = today_durations(rows, now=now)
        self.assertAlmostEqual(100.0, today["discharge_wh"], delta=1)
        self.assertTrue(today["discharge_estimated"])


class ModeEventTests(unittest.TestCase):
    def test_leaving_ups_is_an_anomaly_other_changes_are_system_events(self):
        import events
        state, out = None, []
        for t, mode in enumerate(["UPS", "UPS", None, "Manual", "Auto", "UPS"]):
            evs, state = events.step(state, events.Observation(ok=True, grid_state="on", mode=mode), 1000.0 + t)
            out += [(e["kind"], e["category"], e["text"]) for e in evs]
        self.assertEqual([("mode_left_ups", "anomaly", "Станція вийшла з режиму UPS (зараз: Manual)"),
                          ("mode_changed", "system", "Режим роботи змінився: Manual → Auto"),
                          ("mode_changed", "system", "Режим роботи змінився: Auto → UPS")], out)


class PollCadenceTests(unittest.TestCase):
    def test_default_is_fifteen_seconds(self):
        self.assertEqual(15, server.MIN_POLL_SECONDS)
        self.assertGreaterEqual(server.POLL_SECONDS, server.MIN_POLL_SECONDS)

    def test_six_paced_requests_fit_in_one_interval(self):
        typical_answer = 0.3  # one UDP round trip on the LAN, s
        self.assertEqual(6, len(server.METHODS))
        self.assertLess(len(server.METHODS) * (server.REQUEST_PACING_SECONDS + typical_answer), server.MIN_POLL_SECONDS)

    def test_polls_start_on_a_cadence_and_never_back_to_back(self):
        self.assertAlmostEqual(8.0, server.next_poll_delay(100.0, 107.0, 15))
        self.assertEqual(server.POLL_GAP_SECONDS, server.next_poll_delay(100.0, 131.5, 15))  # worst case: all timeouts


if __name__ == "__main__":
    unittest.main()

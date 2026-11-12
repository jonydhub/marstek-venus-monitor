import os
import tempfile
import unittest

from history import (EVENT_RETENTION_SECONDS, HistoryStore, compute_insights, outage_periods,
                     today_durations)
from tests.test_history import KYIV, row, ts  # noqa: F401


def states(start, seconds, step, **fields):
    return [row(start + i, **fields) for i in range(0, seconds, step)]


class TodayDurationTests(unittest.TestCase):
    def setUp(self):
        self.now = ts("2026-11-10T12:00:00")

    def test_counts_charge_discharge_and_outage_since_local_midnight(self):
        rows = (states(ts("2026-11-10T00:00:00"), 3600, 30, flow_dir="charging", offgrid=0.0)
                + states(ts("2026-11-10T01:00:00"), 7200, 30, flow_dir="discharging", offgrid=121.0)
                + states(ts("2026-11-10T03:00:00"), 1800, 30, flow_dir="idle", offgrid=0.0))
        today = today_durations(rows, now=self.now)
        self.assertAlmostEqual(today["charge_seconds"], 3600, delta=30)
        self.assertAlmostEqual(today["discharge_seconds"], 7200, delta=30)
        self.assertAlmostEqual(today["outage_seconds"], 7200, delta=30)

    def test_gaps_are_not_counted_as_any_state(self):
        rows = states(ts("2026-11-10T01:00:00"), 600, 30, flow_dir="discharging", offgrid=100.0) + \
            states(ts("2026-11-10T05:00:00"), 600, 30, flow_dir="discharging", offgrid=100.0)
        today = today_durations(rows, now=self.now)
        self.assertLess(today["outage_seconds"], 1200)
        self.assertLess(today["discharge_seconds"], 1200)

    def test_interval_straddling_midnight_counts_only_after_midnight(self):
        rows = [row(ts("2026-11-09T23:59:00"), offgrid=50.0, flow_dir="discharging"),
                row(ts("2026-11-10T00:01:00"), offgrid=50.0, flow_dir="discharging")]
        today = today_durations(rows, now=self.now)
        self.assertEqual(today["outage_seconds"], 60)
        self.assertEqual(today["discharge_seconds"], 60)

    def test_outage_uses_backup_output_threshold(self):
        rows = states(ts("2026-11-10T01:00:00"), 600, 30, offgrid=3.0)
        self.assertEqual(today_durations(rows, now=self.now)["outage_seconds"], 0)
        self.assertGreater(today_durations(rows, now=self.now, threshold_w=2.0)["outage_seconds"], 500)

    def test_insights_publish_today_block(self):
        rows = states(ts("2026-11-10T01:00:00"), 600, 30, offgrid=80.0, flow_dir="discharging")
        insights = compute_insights(rows, now=self.now, current_offgrid_w=80.0, temperature_c=20.0)
        self.assertEqual(set(insights["today"]), {"charge_seconds", "discharge_seconds", "outage_seconds", "charge_wh",
                                                  "discharge_wh", "charge_estimated", "discharge_estimated",
                                                  "grid_timeline"})


class OutagePeriodTests(unittest.TestCase):
    def test_merges_samples_with_backup_draw_into_periods(self):
        now = ts("2026-11-10T12:00:00")
        rows = (states(ts("2026-11-10T08:00:00"), 600, 30, offgrid=0.0)
                + states(ts("2026-11-10T08:10:00"), 3600, 30, offgrid=120.0)
                + states(ts("2026-11-10T09:10:00"), 600, 30, offgrid=0.0))
        periods = outage_periods(rows, now=now)
        self.assertEqual(len(periods), 1)
        self.assertEqual(periods[0]["start"], "2026-11-10T08:10:00+02:00")
        self.assertTrue(periods[0]["end"].startswith("2026-11-10T09:10"))

    def test_gap_splits_a_period(self):
        now = ts("2026-11-10T12:00:00")
        rows = states(ts("2026-11-10T08:00:00"), 600, 30, offgrid=120.0) + \
            states(ts("2026-11-10T10:00:00"), 600, 30, offgrid=120.0)
        self.assertEqual(len(outage_periods(rows, now=now)), 2)


class EventStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = HistoryStore(os.path.join(self.tmp.name, "h.db"))

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def ev(self, t, kind="light_lost", category="light"):
        return {"ts": t, "kind": kind, "category": category, "text": kind, "value": None}

    def test_newest_first_with_limit_and_category_filter(self):
        for i, (kind, category) in enumerate([("light_lost", "light"), ("connection_lost", "connection"),
                                              ("anomaly_high_discharge", "anomaly")]):
            self.store.record_event(self.ev(1000.0 + i, kind, category))
        self.assertEqual([e["kind"] for e in self.store.events(limit=2)],
                         ["anomaly_high_discharge", "connection_lost"])
        self.assertEqual([e["kind"] for e in self.store.events(category="connection")], ["connection_lost"])

    def test_events_kept_30_days(self):
        now = 100 * 86400.0
        self.store.record_event(self.ev(now - EVENT_RETENTION_SECONDS - 10))
        self.store.record_event(self.ev(now - 3600))
        self.store.prune(now)
        self.assertEqual(len(self.store.events(limit=10)), 1)
        self.assertEqual(EVENT_RETENTION_SECONDS, 30 * 86400)


if __name__ == "__main__":
    unittest.main()

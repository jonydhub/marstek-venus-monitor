"""Regression: real Venus E history (fw148, Manual, grid present, 100 %) — the standby top-up cycle.

1.3.1 turned each 1 % SOC step into a fake −335…−711 W «discharge», raised «Розряд … при наявності мережі …
можливий конфлікт з ДБЖ/інвертором», then «Почалось заряджання ≈ 11 Вт (оцінка)» and «Батарею заряджено».
In reality the battery self-drains one step and tops up from the grid (ongrid_power ≈ −1195 W = charging).
"""
import json
import unittest
from pathlib import Path

import events
from history import standby_consumption
from server import BATTERY_TREND_WINDOW_SECONDS, calculate_battery_flow

FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "standby_topup_fw148.json").read_text(encoding="utf-8"))
DAY = 1_700_000_000.0  # any epoch base; only differences matter


def replay():
    """Feed the samples through the poller's estimator + journal detector exactly as server.py does."""
    rated = FIXTURE["rated_wh"]
    window, state, journal, flows = [], None, [], []
    for row in FIXTURE["samples"]:
        now = DAY + row["t"]
        window.append((now, row["bat_wh"]))
        window = [w for w in window if now - w[0] <= BATTERY_TREND_WINDOW_SECONDS]
        flow = calculate_battery_flow(window, row["bat_wh"], rated, ongrid_w=row["ongrid_w"], offgrid_w=row["offgrid_w"])
        flows.append((row["clock"], flow))
        grid = "off" if abs(row["offgrid_w"]) >= 5 else "on"
        obs = events.Observation(ok=True, grid_state=grid, direction=flow["direction"],
                                 battery_w=flow["power_w"] if flow["available"] else None,
                                 battery_measured=flow["estimated"] is False, backup_w=row["offgrid_w"],
                                 soc=row["soc"], temp_c=25.0)
        new, state = events.step(state, obs, now)
        journal.extend(new)
    return flows, journal


class StandbyTopUpRegressionTests(unittest.TestCase):
    def test_no_false_discharge_anywhere(self):
        flows, _ = replay()
        self.assertEqual([c for c, f in flows if f["direction"] == "discharging"], [])

    def test_top_up_is_measured_charging_from_the_grid(self):
        flows = dict(replay()[0])
        for clock, watts in (("07:53:37", 1195.0), ("08:33:10", 1195.0), ("09:11:13", 637.0)):
            self.assertEqual(flows[clock]["direction"], "charging", clock)
            self.assertFalse(flows[clock]["estimated"], clock)
            self.assertAlmostEqual(flows[clock]["power_w"], watts, delta=0.1)

    def test_single_soc_step_is_idle_not_a_trend(self):
        flows = dict(replay()[0])
        for clock in ("07:54:16", "07:56:09", "07:58:03", "08:33:47", "08:36:55", "09:12:27", "09:14:20"):
            self.assertEqual(flows[clock]["direction"], "idle", clock)
            self.assertTrue(flows[clock]["estimated"], clock)

    def test_journal_has_no_anomaly_and_at_most_one_maintenance_record(self):
        _, journal = replay()
        kinds = [e["kind"] for e in journal]
        self.assertNotIn("anomaly_discharge_on_grid", kinds)
        self.assertNotIn("anomaly_high_discharge", kinds)
        self.assertNotIn("charging_started", kinds)
        self.assertNotIn("charged_full", kinds)
        self.assertLessEqual(kinds.count("maintenance_topup"), 1)
        self.assertEqual(set(kinds) - {"maintenance_topup"}, set())
        self.assertFalse(any("оцінка" in e["text"] or "конфлікт" in e["text"] for e in journal))

    def test_standby_self_consumption_from_top_up_cadence(self):
        rows = [{"ts": DAY + r["t"], "soc": r["soc"], "bat_wh": r["bat_wh"], "offgrid_w": r["offgrid_w"],
                 "ongrid_w": r["ongrid_w"]} for r in FIXTURE["samples"]]
        watts = standby_consumption(rows, now=rows[-1]["ts"] + 60)
        self.assertIsNotNone(watts)
        self.assertTrue(70 <= watts <= 95, watts)  # 52 Wh per ~38 min ≈ 82 W

    def test_standby_needs_a_full_cycle(self):
        rows = [{"ts": DAY + r["t"], "soc": r["soc"], "bat_wh": r["bat_wh"], "offgrid_w": 0.0}
                for r in FIXTURE["samples"][:3]]
        self.assertIsNone(standby_consumption(rows, now=rows[-1]["ts"]))


class MeasuredFlowTests(unittest.TestCase):
    def test_negative_ongrid_is_charging_positive_is_discharging(self):
        self.assertEqual(calculate_battery_flow([], 2000.0, 5120.0, ongrid_w=-800)["direction"], "charging")
        out = calculate_battery_flow([], 2000.0, 5120.0, ongrid_w=450)
        self.assertEqual((out["direction"], out["power_w"], out["estimated"]), ("discharging", -450.0, False))

    def test_backup_output_is_measured_discharge(self):
        out = calculate_battery_flow([], 2000.0, 5120.0, ongrid_w=0, offgrid_w=300)
        self.assertEqual((out["direction"], out["power_w"], out["method"]), ("discharging", -300.0, "measured_ac_power"))

    def test_two_consistent_soc_steps_are_a_real_trend(self):
        q = 51.2
        samples = [(0, 3000.0), (300, 3000.0), (600, 3000.0 - q), (900, 3000.0 - q), (1200, 3000.0 - 2 * q)]
        out = calculate_battery_flow(samples, 3000.0 - 2 * q, 5120.0, ongrid_w=0, offgrid_w=0)
        self.assertEqual(out["direction"], "discharging")
        self.assertAlmostEqual(out["power_w"], -q / 600 * 3600, delta=1.0)  # ≈ −307 W
        self.assertTrue(out["estimated"])


if __name__ == "__main__":
    unittest.main()

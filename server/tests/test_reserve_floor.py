import unittest

from outage import build_outage_status, parse_gpv_html
from tests.test_outage import GPV_HTML, at


def status(**overrides):
    kwargs = dict(
        now=at("2026-10-17T20:30:00"), group="1.1", building=None, schedules=[parse_gpv_html(GPV_HTML)],
        accidents=[], grid_state="off", stored_wh=0.63 * 5120, battery_flow={"direction": "discharging", "power_w": -130.0},
        expected_load_w=None, fetched_at=None, error=None,
    )
    kwargs.update(overrides)
    return build_outage_status(**kwargs)


class ReserveFloorTests(unittest.TestCase):
    def test_runtime_uses_energy_above_the_discharge_floor_and_measured_backup_output(self):
        a = status(backup_output_w=121.0, rated_wh=5120.0, floor_pct=11)["autonomy"]
        self.assertEqual(a["load_basis"], "measured_backup_output")
        self.assertEqual(a["load_w"], 121.0)
        self.assertEqual(a["floor_pct"], 11)
        self.assertEqual(a["floor_wh"], 563)
        self.assertEqual(a["usable_wh"], 2662)
        # (3225.6 − 563.2) Wh / 121 W = 22.0 h (before: 3225.6 / 121 = 26.66 h, to 0 %)
        self.assertEqual(a["runtime_seconds"], round((0.63 * 5120 - 563.2) / 121 * 3600))

    def test_capacity_trend_is_the_fallback_load_during_an_outage(self):
        a = status(backup_output_w=None, rated_wh=5120.0, floor_pct=11)["autonomy"]
        self.assertEqual(a["load_basis"], "measured_discharge")
        self.assertEqual(a["load_w"], 130.0)

    def test_backup_output_below_draw_threshold_is_not_a_load(self):
        a = status(grid_state="on", backup_output_w=0.0, battery_flow={"direction": "idle", "power_w": 0.0},
                   expected_load_w=300.0, rated_wh=5120.0, floor_pct=11)["autonomy"]
        self.assertEqual(a["load_basis"], "configured_average")

    def test_rated_capacity_is_derived_from_soc_when_missing(self):
        a = status(backup_output_w=121.0, rated_wh=None, soc=63, floor_pct=11)["autonomy"]
        self.assertEqual(a["floor_wh"], 563)

    def test_below_floor_means_zero_runtime(self):
        a = status(stored_wh=400.0, backup_output_w=121.0, rated_wh=5120.0, floor_pct=11)["autonomy"]
        self.assertEqual(a["usable_wh"], 0)
        self.assertEqual(a["runtime_seconds"], 0)
        self.assertFalse(a["covers_next_outage"])

    def test_no_floor_without_capacity_information_keeps_old_math(self):
        a = status(backup_output_w=121.0)["autonomy"]
        self.assertIsNone(a["floor_wh"])
        self.assertEqual(a["usable_wh"], round(0.63 * 5120))


class ScheduleWindowsTests(unittest.TestCase):
    def test_status_lists_today_windows_for_the_schedule_bar(self):
        windows = status(grid_state="on")["windows"]
        self.assertTrue(windows)
        self.assertEqual(windows[0]["kind"], "off")
        self.assertEqual({"start", "end", "kind"}, set(windows[0]))

    def test_maybe_windows_are_marked(self):
        windows = status(grid_state="on", maybe=[("2026-10-18T10:00:00+03:00", "2026-10-18T12:00:00+03:00")])["windows"]
        self.assertIn("maybe", [w["kind"] for w in windows])


if __name__ == "__main__":
    unittest.main()

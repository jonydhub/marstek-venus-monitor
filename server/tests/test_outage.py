import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from outage import (
    building_matches,
    build_outage_status,
    detect_grid_state,
    parse_gpv_html,
)

KYIV = ZoneInfo("Europe/Kyiv")

# Real LOE payload (api.loe.lviv.ua menu_items/1108, 17.10.2026).
GPV_HTML = (
    "<div><p><b>Графік погодинних відключень на 17.10.2026</b></p>\n"
    "<p><b>Інформація станом на 18:56 17.10.2026</b></p>\n"
    "<p>Група 1.1. Електроенергії немає з 20:00 до 22:00.</p>\n"
    "<p>Група 1.2. Електроенергії немає з 11:00 до 14:00.</p>\n"
    "<p>Група 4.2. Електроенергії немає з 10:00 до 12:00, з 20:00 до 24:00.</p>\n"
    "<p>Група 6.2. Електроенергія є.</p>\n</div>"
)

# Real LOE accident shape (power-api.loe.lviv.ua/api/pw_accidents), street/building adapted.
ACCIDENT = {
    "dateEvent": "2026-11-09T14:30:18+02:00",
    "datePlanIn": "2026-11-09T17:00:00+02:00",
    "koment": "заміна вводу",
    "typeOff": 0,
    "buildingNames": "58, 60, 60-А, 62",
}


def at(text):
    return datetime.fromisoformat(text).replace(tzinfo=KYIV)


class ParseScheduleTests(unittest.TestCase):
    def test_parses_date_update_time_and_group_intervals(self):
        schedule = parse_gpv_html(GPV_HTML)
        self.assertEqual(schedule["date"], "2026-10-17")
        self.assertEqual(schedule["updated_at"], "2026-10-17T18:56:00+03:00")
        self.assertEqual(schedule["groups"]["1.1"], [("2026-10-17T20:00:00+03:00", "2026-10-17T22:00:00+03:00")])
        self.assertEqual(schedule["groups"]["6.2"], [])

    def test_parses_several_intervals_and_midnight_end(self):
        schedule = parse_gpv_html(GPV_HTML)
        self.assertEqual(schedule["groups"]["4.2"], [
            ("2026-10-17T10:00:00+03:00", "2026-10-17T12:00:00+03:00"),
            ("2026-10-17T20:00:00+03:00", "2026-10-18T00:00:00+03:00"),
        ])

    def test_empty_html_means_no_published_schedule(self):
        self.assertIsNone(parse_gpv_html(""))
        self.assertIsNone(parse_gpv_html(None))


class BuildingMatchTests(unittest.TestCase):
    def test_matches_exact_building_token_only(self):
        self.assertTrue(building_matches("58, 60, 62", "60"))
        self.assertFalse(building_matches("160, 60-А", "60"))
        self.assertFalse(building_matches("", "60"))


class GridStateTests(unittest.TestCase):
    def test_backup_output_power_means_running_on_battery(self):
        self.assertEqual(detect_grid_state(offgrid_power_w=42, ongrid_power_w=0, device_ok=True), "off")

    def test_no_backup_draw_means_grid_present(self):
        self.assertEqual(detect_grid_state(offgrid_power_w=0, ongrid_power_w=0, device_ok=True), "on")

    def test_unknown_without_device_data(self):
        self.assertEqual(detect_grid_state(offgrid_power_w=None, ongrid_power_w=None, device_ok=False), "unknown")


class OutageStatusTests(unittest.TestCase):
    def base(self, **overrides):
        kwargs = dict(
            now=at("2026-10-17T19:00:00"),
            group="1.1",
            building="60",
            schedules=[parse_gpv_html(GPV_HTML)],
            accidents=[],
            grid_state="on",
            stored_wh=2560.0,
            battery_flow={"direction": "idle", "power_w": 0.0},
            expected_load_w=None,
            fetched_at="2026-10-17T18:59:00+03:00",
            error=None,
        )
        kwargs.update(overrides)
        return build_outage_status(**kwargs)

    def test_reports_next_scheduled_outage_and_duration(self):
        status = self.base()
        self.assertTrue(status["available"])
        self.assertEqual(status["grid_state"], "on")
        self.assertEqual(status["next_outage"]["start"], "2026-10-17T20:00:00+03:00")
        self.assertEqual(status["next_outage"]["duration_seconds"], 7200)
        self.assertIsNone(status["current_outage"])

    def test_without_load_value_autonomy_stays_unknown(self):
        autonomy = self.base()["autonomy"]
        self.assertIsNone(autonomy["runtime_seconds"])
        self.assertIsNone(autonomy["covers_next_outage"])
        self.assertIsNone(autonomy["load_basis"])

    def test_configured_average_load_predicts_coverage(self):
        autonomy = self.base(expected_load_w=640.0)["autonomy"]
        self.assertEqual(autonomy["load_basis"], "configured_average")
        self.assertEqual(autonomy["runtime_seconds"], 14400)
        self.assertTrue(autonomy["covers_next_outage"])

    def test_insufficient_reserve_is_reported(self):
        autonomy = self.base(stored_wh=500.0, expected_load_w=640.0)["autonomy"]
        self.assertFalse(autonomy["covers_next_outage"])

    def test_measured_discharge_wins_while_on_battery(self):
        status = self.base(
            now=at("2026-10-17T20:30:00"),
            grid_state="off",
            battery_flow={"direction": "discharging", "power_w": -1280.0},
            expected_load_w=100.0,
        )
        self.assertEqual(status["current_outage"]["end"], "2026-10-17T22:00:00+03:00")
        self.assertEqual(status["autonomy"]["load_basis"], "measured_discharge")
        self.assertEqual(status["autonomy"]["runtime_seconds"], 7200)
        self.assertTrue(status["autonomy"]["covers_next_outage"])

    def test_measured_average_is_used_when_grid_on_and_no_configured_load(self):
        autonomy = self.base(measured_load_w=40.0)["autonomy"]
        self.assertEqual(autonomy["load_basis"], "measured_average_24h")
        self.assertEqual(autonomy["load_w"], 40.0)
        self.assertEqual(autonomy["runtime_seconds"], 230400)
        self.assertTrue(autonomy["covers_next_outage"])

    def test_configured_load_overrides_measured_average(self):
        autonomy = self.base(expected_load_w=640.0, measured_load_w=40.0)["autonomy"]
        self.assertEqual(autonomy["load_basis"], "configured_average")
        self.assertEqual(autonomy["load_w"], 640.0)

    def test_measured_discharge_still_wins_during_outage(self):
        status = self.base(
            now=at("2026-10-17T20:30:00"), grid_state="off",
            battery_flow={"direction": "discharging", "power_w": -1280.0}, measured_load_w=40.0,
        )
        self.assertEqual(status["autonomy"]["load_basis"], "measured_discharge")

    def test_measured_average_absent_keeps_forecast_unknown(self):
        autonomy = self.base(measured_load_w=None)["autonomy"]
        self.assertIsNone(autonomy["load_basis"])
        self.assertIsNone(autonomy["runtime_seconds"])

    def test_matching_accident_is_current_outage(self):
        status = self.base(now=at("2026-11-09T15:00:00"), schedules=[], accidents=[ACCIDENT])
        self.assertEqual(status["current_outage"]["kind"], "loe_outage")
        self.assertEqual(status["current_outage"]["end"], "2026-11-09T17:00:00+02:00")

    def test_accident_for_other_building_is_ignored(self):
        other = {**ACCIDENT, "buildingNames": "1, 3"}
        status = self.base(now=at("2026-11-09T15:00:00"), schedules=[], accidents=[other])
        self.assertIsNone(status["current_outage"])

    def test_no_published_schedule_is_not_an_outage(self):
        status = self.base(schedules=[])
        self.assertFalse(status["schedule_published"])
        self.assertIsNone(status["next_outage"])

    def test_source_error_keeps_local_state_and_reports_error(self):
        status = self.base(schedules=[], error="timeout", fetched_at=None)
        self.assertEqual(status["error"], "timeout")
        self.assertEqual(status["grid_state"], "on")


if __name__ == "__main__":
    unittest.main()


class FetchShapeTests(unittest.TestCase):
    def test_schedule_fetch_accepts_plain_list_payload(self):
        from unittest import mock
        import outage as o
        with mock.patch.object(o, "_get_json", return_value=[{"menuItems": [{"name": "Today", "rawHtml": None}]}, "junk"]):
            self.assertEqual(o.fetch_schedules(), [])

"""1.3.7 Windows toasts: high-load threshold from the 7-day real load, overload trip, leaving UPS mode, UA units."""
import unittest

from tests import _path  # noqa: F401
from tests.test_alerts import T0, Harness, status
from mvm import alerts
from mvm.snapshot import kwh, watts


def with_extra(st, mean=None, coverage=0, trip=None, mode=None):
    st["insights"].update({"backup_load_mean_w_7d": mean, "backup_load_coverage_seconds_7d": coverage})
    st["power_outage"]["grid_detail"] = {"overload_trip": trip}
    if mode:
        st["methods"]["ES.GetMode"] = {"ok": True, "data": {"mode": mode}}
    return st


class HighLoadTests(unittest.TestCase):
    def evaluate(self, offgrid, **extra):
        h = Harness()
        h.step(status())
        res = alerts.evaluate(h.memory, with_extra(status(grid="off", offgrid=offgrid), **extra), T0, h.enabled)
        return [a for a in res.events if a.id == "high_load"]

    def test_threshold_is_twice_the_seven_day_mean_of_real_load(self):
        self.assertEqual([], self.evaluate(500.0, mean=300.0, coverage=7200))  # limit 600 W
        alert = self.evaluate(650.0, mean=300.0, coverage=7200)[0]
        self.assertIn("поріг 600 Вт, середнє 300 Вт за 7 дн", alert.text)

    def test_no_average_text_from_less_than_30_minutes_of_load(self):
        alert = self.evaluate(250.0, mean=7.0, coverage=600)[0]
        self.assertIn("поріг 200 Вт; оцінка", alert.text)
        self.assertNotIn("середнє", alert.text)


class TripAndModeTests(unittest.TestCase):
    def test_overload_trip_toast_once_per_trip(self):
        h = Harness()
        h.step(with_extra(status(grid="off", offgrid=779.0), mode="UPS"))
        trip = {"last_w": 779, "at": "2026-11-11T22:08:35+02:00"}
        res = alerts.evaluate(h.memory, with_extra(status(grid="off", offgrid=0.0), trip=trip, mode="UPS"), T0, h.enabled)
        toast = [a for a in res.events if a.id == "overload_trip"]
        self.assertEqual(1, len(toast))
        self.assertIn("останнє значення 779 Вт", toast[0].text)
        self.assertIn("якщо там 405 — зменшіть пускове навантаження", toast[0].text)
        again = alerts.evaluate(res.memory, with_extra(status(grid="off", offgrid=0.0), trip=trip, mode="UPS"), T0, h.enabled)
        self.assertEqual([], [a for a in again.events if a.id == "overload_trip"])

    def test_leaving_ups_mode(self):
        h = Harness()
        h.step(with_extra(status(), mode="UPS"))
        self.assertIn("mode_left_ups", h.step(with_extra(status(), mode="Manual")))
        self.assertNotIn("mode_left_ups", h.step(with_extra(status(), mode="Auto")))


class UnitTests(unittest.TestCase):
    def test_ukrainian_units(self):
        self.assertEqual("5,12 кВт·год", kwh(5120))
        self.assertEqual("779 Вт", watts(-779))


if __name__ == "__main__":
    unittest.main()

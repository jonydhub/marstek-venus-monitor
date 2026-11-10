import unittest

from server import calculate_battery_flow


class BatteryFlowEstimateTests(unittest.TestCase):
    def test_requires_at_least_two_minutes_of_samples(self):
        result = calculate_battery_flow([(0.0, 1000.0), (119.0, 1020.0)], 1020.0, 5120.0)
        self.assertFalse(result["available"])
        self.assertIsNone(result["charge_eta_seconds"])

    def test_calculates_charge_rate_and_time_to_full(self):
        samples = [(0.0, 1000.0), (60.0, 1010.0), (120.0, 1020.0), (300.0, 1050.0)]
        result = calculate_battery_flow(samples, 1050.0, 5120.0)
        self.assertTrue(result["available"])
        self.assertEqual(result["direction"], "charging")
        self.assertAlmostEqual(result["power_w"], 600.0, delta=1.0)
        self.assertAlmostEqual(result["charge_eta_seconds"], (5120.0 - 1050.0) / 600.0 * 3600.0, delta=5.0)
        self.assertIsNone(result["discharge_eta_seconds"])

    def test_calculates_discharge_rate_and_time_to_zero(self):
        samples = [(0.0, 3000.0), (60.0, 2995.0), (120.0, 2990.0), (300.0, 2975.0)]
        result = calculate_battery_flow(samples, 2975.0, 5120.0)
        self.assertTrue(result["available"])
        self.assertEqual(result["direction"], "discharging")
        self.assertAlmostEqual(result["power_w"], -300.0, delta=1.0)
        self.assertAlmostEqual(result["discharge_eta_seconds"], 2975.0 / 300.0 * 3600.0, delta=10.0)
        self.assertIsNone(result["charge_eta_seconds"])

    def test_reports_idle_without_fabricating_an_eta(self):
        result = calculate_battery_flow([(0.0, 2048.0), (150.0, 2048.0), (300.0, 2048.0)], 2048.0, 5120.0)
        self.assertTrue(result["available"])
        self.assertEqual(result["direction"], "idle")
        self.assertEqual(result["power_w"], 0.0)
        self.assertIsNone(result["charge_eta_seconds"])
        self.assertIsNone(result["discharge_eta_seconds"])

    def test_full_battery_has_zero_charge_time(self):
        result = calculate_battery_flow([(0.0, 5000.0), (150.0, 5010.0), (300.0, 5020.0)], 5120.0, 5120.0)
        self.assertEqual(result["charge_eta_seconds"], 0.0)


if __name__ == "__main__":
    unittest.main()

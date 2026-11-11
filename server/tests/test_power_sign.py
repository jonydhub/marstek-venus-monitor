import unittest

import server


class PowerSignTests(unittest.TestCase):
    def test_uint16_wraparound_folded(self):
        d = server.normalize_power_fields({"offgrid_power": 65522, "ongrid_power": -2490, "bat_soc": 99})
        self.assertEqual(d["offgrid_power"], -14)
        self.assertEqual(d["ongrid_power"], -2490)
        self.assertEqual(d["bat_soc"], 99)

    def test_normal_values_untouched(self):
        self.assertEqual(server.normalize_power_fields({"offgrid_power": 438})["offgrid_power"], 438)
        self.assertEqual(server.normalize_power_fields({"ongrid_power": 32767})["ongrid_power"], 32767)

    def test_non_dict(self):
        self.assertEqual(server.normalize_power_fields([1]), [1])


if __name__ == "__main__":
    unittest.main()

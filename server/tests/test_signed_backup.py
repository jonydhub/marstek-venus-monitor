"""Real outage test (ATS → battery): ES.GetStatus offgrid_power is POSITIVE while the backup output
feeds the house (+1333, +1330, +396, +1895 W). Small negative readings (uint16-folded 65522 → -14 W)
appear with the grid present and are noise, never a load."""
import unittest

import server
from events import Observation, step
from history import measured_backup_load, today_durations
from outage import _autonomy, detect_grid_state
from tests.test_history import row, ts


class SignedBackupRuleTests(unittest.TestCase):
    def test_negative_offgrid_is_grid_on(self):
        self.assertEqual(detect_grid_state(-14, 0, True), "on")
        self.assertEqual(detect_grid_state(-1330, 0, True), "on")

    def test_positive_offgrid_is_outage(self):
        for watts in (1333, 1330, 1327, 396, 1895):
            self.assertEqual(detect_grid_state(watts, 0, True), "off")

    def test_negative_backup_never_journals_light_lost(self):
        state = None
        events = []
        for i, backup in enumerate((0.0, -14.0, -14.0, -12.0)):
            grid = detect_grid_state(backup, 0, True)
            obs = Observation(ok=True, grid_state=grid, direction="idle", battery_w=0.0, backup_w=backup, soc=98)
            out, state = step(state, obs, 1000.0 + 30 * i, 2000.0)
            events.extend(out)
        self.assertNotIn("light_lost", [e["kind"] for e in events])

    def test_positive_backup_journals_light_lost_with_watts(self):
        state, events = None, []
        for i, backup in enumerate((0.0, 1330.0)):
            obs = Observation(ok=True, grid_state=detect_grid_state(backup, 0, True), direction="discharging",
                              battery_w=-1330.0, backup_w=backup, soc=98, battery_measured=True)
            out, state = step(state, obs, 1000.0 + 30 * i, 2000.0)
            events.extend(out)
        lost = [e for e in events if e["kind"] == "light_lost"]
        self.assertEqual(len(lost), 1)
        self.assertIn("1330 Вт", lost[0]["text"])

    def test_negative_offgrid_is_not_battery_output(self):
        self.assertIsNone(server._signed_ac_battery_power(0, -14))
        self.assertEqual(server._signed_ac_battery_power(0, 1330), -1330)

    def test_history_ignores_negative_backup_samples(self):
        now = ts("2026-11-10T12:00:00")
        start = ts("2026-11-10T09:00:00")
        rows = [row(start + i, offgrid=-14.0) for i in range(0, 3600, 30)]
        self.assertEqual(today_durations(rows, now=now)["outage_seconds"], 0)
        self.assertIsNone(measured_backup_load(rows, now=now))
        rows = [row(start + i, offgrid=1330.0) for i in range(0, 3600, 30)]
        self.assertAlmostEqual(measured_backup_load(rows, now=now), 1330.0)

    def test_autonomy_uses_positive_backup_above_floor(self):
        result = _autonomy("off", 5030, {}, None, {}, backup_output_w=1330, rated_wh=5120, floor_pct=11)
        self.assertEqual(result["load_basis"], "measured_backup_output")
        self.assertEqual(result["load_w"], 1330)
        # (5030 − 563.2) / 1330 h ≈ 3 h 21.5 min
        self.assertAlmostEqual(result["usable_wh"], 4467, delta=1)


if __name__ == "__main__":
    unittest.main()

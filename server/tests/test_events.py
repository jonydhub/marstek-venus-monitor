import unittest

from events import DetectorState, Observation, step

DOWN = Observation(ok=False)


def up(grid="on", direction="idle", battery_w=0.0, backup_w=0.0, soc=80, temp_c=25.0):
    return Observation(ok=True, grid_state=grid, direction=direction, battery_w=battery_w, backup_w=backup_w,
                       soc=soc, temp_c=temp_c)


def run(observations, start=1000.0, step_s=30.0, threshold=2000.0):
    state, out = None, []
    for i, obs in enumerate(observations):
        events, state = step(state, obs, start + i * step_s, threshold)
        out.extend(events)
    return out, state


def kinds(events):
    return [e["kind"] for e in events]


class LightFromBackupOutputOnlyTests(unittest.TestCase):
    def test_backup_output_draw_is_light_lost_and_idle_is_returned(self):
        events, _ = run([up(), up(grid="off", backup_w=121.0, direction="discharging", battery_w=-130.0),
                         up(grid="on")])
        self.assertEqual(kinds(events), ["light_lost", "light_returned"])
        self.assertIn("121 Вт", events[0]["text"])
        self.assertEqual(events[0]["category"], "light")

    def test_lost_connection_is_never_light_lost(self):
        events, state = run([up(), DOWN, DOWN, DOWN, DOWN])
        self.assertEqual(kinds(events), ["connection_lost"])
        self.assertEqual(events[0]["category"], "connection")
        self.assertIn("стан світла невідомий", events[0]["text"])
        self.assertEqual(state.grid, "on")  # last known kept, not flipped to off

    def test_two_failures_are_not_yet_a_lost_connection(self):
        events, _ = run([up(), DOWN, DOWN, up()])
        self.assertEqual(kinds(events), [])

    def test_restore_is_journaled_separately_and_light_stays_on(self):
        events, _ = run([up(), DOWN, DOWN, DOWN, up()])
        self.assertEqual(kinds(events), ["connection_lost", "connection_restored"])

    def test_unknown_grid_state_does_not_change_light(self):
        events, _ = run([up(), up(grid="unknown"), up(grid="on")])
        self.assertEqual(kinds(events), [])

    def test_light_lost_during_gap_is_reported_from_backup_output_after_restore(self):
        events, _ = run([up(), DOWN, DOWN, DOWN, up(grid="off", backup_w=60.0)])
        self.assertEqual(kinds(events), ["connection_lost", "connection_restored", "light_lost"])


class ChargingEventsTests(unittest.TestCase):
    def test_charging_started_stopped_and_full(self):
        events, _ = run([up(direction="idle", soc=50), up(direction="charging", battery_w=800, soc=60),
                         up(direction="idle", soc=70), up(direction="charging", battery_w=800, soc=98),
                         up(direction="charging", battery_w=300, soc=100), up(direction="idle", soc=100)])
        self.assertEqual(kinds(events), ["charging_started", "charging_stopped", "charging_started", "charged_full"])


class AnomalyTests(unittest.TestCase):
    def test_discharge_above_threshold_fires_once_and_rearms(self):
        obs = up(grid="off", backup_w=2128.0, direction="discharging", battery_w=-2200.0)
        events, _ = run([up(), obs, obs, up(grid="off", backup_w=300.0, direction="discharging", battery_w=-320.0),
                         obs])
        high = [e for e in events if e["kind"] == "anomaly_high_discharge"]
        self.assertEqual(len(high), 2)
        self.assertIn("перевищує поріг 2000 Вт", high[0]["text"])
        self.assertEqual(high[0]["category"], "anomaly")

    def test_threshold_is_clamped_to_200_10000(self):
        obs = up(grid="off", backup_w=250.0, direction="discharging", battery_w=-260.0)
        events, _ = run([up(), obs], threshold=50)
        self.assertIn("anomaly_high_discharge", kinds(events))

    def test_discharge_while_grid_present_and_backup_idle(self):
        obs = up(grid="on", backup_w=0.0, direction="discharging", battery_w=-177.0)
        events, _ = run([up(), obs, obs])
        self.assertEqual(kinds(events), ["anomaly_discharge_on_grid"])
        self.assertIn("177 Вт", events[0]["text"])

    def test_high_temperature_with_hysteresis(self):
        events, _ = run([up(temp_c=40), up(temp_c=46), up(temp_c=44), up(temp_c=41), up(temp_c=46)])
        self.assertEqual(kinds(events).count("anomaly_temperature"), 2)

    def test_state_is_a_frozen_value(self):
        _, state = run([up()])
        self.assertIsInstance(state, DetectorState)


if __name__ == "__main__":
    unittest.main()

import unittest
from datetime import datetime, timedelta, timezone

from tests import _path  # noqa: F401
from mvm import alerts

T0 = datetime(2026, 11, 10, 12, 0, tzinfo=timezone.utc)
_seq = iter(range(1, 1_000_000))


def status(soc=50, direction="idle", grid="on", ok=True, temp=25.0, next_start=None, next_minutes=120,
           covers=None, runtime=None, current_end=None, bat_wh=2500.0, rated=5120.0, offgrid=0.0, power=None):
    n = next(_seq)
    next_outage = None
    if next_start is not None:
        end = next_start + timedelta(minutes=next_minutes)
        next_outage = {"start": next_start.isoformat(), "end": end.isoformat(),
                       "duration_seconds": next_minutes * 60, "remaining_seconds": next_minutes * 60}
    current = None
    if current_end is not None:
        current = {"start": (current_end - timedelta(hours=2)).isoformat(), "end": current_end.isoformat()}
    return {
        "collected_at": f"2026-11-10T12:00:{n % 60:02d}.{n:06d}+00:00",
        "methods": {
            "Bat.GetStatus": {"ok": ok, "data": {"soc": soc, "bat_capacity": bat_wh, "rated_capacity": rated,
                                                   "bat_temp": temp}} if ok else {"ok": False, "error": "No response"},
            "ES.GetStatus": {"ok": ok, "data": {"bat_soc": soc, "offgrid_power": offgrid, "ongrid_power": 0}}
            if ok else {"ok": False, "error": "No response"},
        },
        "battery_flow": {"available": direction != "unknown", "direction": direction, "power_w": power,
                         "charge_eta_seconds": 3600 if direction == "charging" else None,
                         "discharge_eta_seconds": 7200 if direction == "discharging" else None},
        "power_outage": {"available": True, "grid_state": grid, "group": "3.1", "next_outage": next_outage,
                         "current_outage": current, "schedule_published": True,
                         "autonomy": {"covers_next_outage": covers, "runtime_seconds": runtime, "load_w": 300.0}},
        "insights": {"backup_avg_w_24h": 150.0},
    }


class Harness:
    def __init__(self, enabled=None):
        self.memory = alerts.Memory()
        self.enabled = enabled or {i: True for i in alerts.ALERT_IDS}

    def step(self, st, now=T0):
        result = alerts.evaluate(self.memory, st, now, self.enabled)
        self.memory = result.memory
        return [a.id for a in result.events]


class StaleRuleTests(unittest.TestCase):
    def test_single_dropped_poll_is_not_stale(self):
        h = Harness()
        h.step(status())
        self.assertEqual([], h.step(status(ok=False)))
        self.assertEqual([], h.step(status()))
        self.assertEqual([], h.step(status(ok=False)))
        self.assertEqual([], h.step(status(ok=False)))
        self.assertEqual([], h.step(status()))

    def test_three_consecutive_failures_alert_once(self):
        h = Harness()
        h.step(status())
        self.assertEqual([], h.step(status(ok=False)))
        self.assertEqual([], h.step(status(ok=False)))
        self.assertEqual(["data_stale"], h.step(status(ok=False)))
        self.assertEqual([], h.step(status(ok=False)))
        self.assertEqual([], h.step(status()))
        for _ in range(2):
            h.step(status(ok=False))
        self.assertEqual(["data_stale"], h.step(status(ok=False)))

    def test_no_stale_alert_before_any_fresh_data(self):
        h = Harness()
        for _ in range(5):
            self.assertEqual([], h.step(status(ok=False)))

    def test_failed_polls_do_not_fake_light_changes(self):
        h = Harness()
        h.step(status(grid="on"))
        for _ in range(4):
            st = status(ok=False)
            st["power_outage"]["grid_state"] = "unknown"
            h.step(st)
        self.assertEqual([], h.step(status(grid="on")))


class LightTests(unittest.TestCase):
    def test_light_lost_and_returned_once(self):
        h = Harness()
        h.step(status(grid="on"))
        self.assertIn("light_lost", h.step(status(grid="off", offgrid=180.0, direction="discharging")))
        self.assertEqual([], h.step(status(grid="off", offgrid=180.0, direction="discharging")))
        self.assertIn("light_returned", h.step(status(grid="on", soc=40), now=T0 + timedelta(hours=2)))
        self.assertEqual([], h.step(status(grid="on", soc=40)))

    def test_first_observation_is_not_a_transition(self):
        self.assertEqual([], Harness().step(status(grid="off", offgrid=150.0)))

    def test_light_lost_text_mentions_soc(self):
        h = Harness()
        h.step(status(grid="on"))
        res = alerts.evaluate(h.memory, status(grid="off", soc=77, offgrid=200.0), T0, h.enabled)
        lost = [a for a in res.events if a.id == "light_lost"][0]
        self.assertEqual("Світло зникло", lost.title)
        self.assertIn("77%", lost.text)


class SocTests(unittest.TestCase):
    def test_low_soc_with_hysteresis(self):
        h = Harness()
        h.step(status(soc=30))
        self.assertEqual(["soc_low"], h.step(status(soc=19)))
        self.assertEqual([], h.step(status(soc=18)))
        self.assertEqual([], h.step(status(soc=21)))
        self.assertEqual([], h.step(status(soc=19)))
        h.step(status(soc=26))
        self.assertEqual(["soc_low"], h.step(status(soc=19)))

    def test_critical_soc_once(self):
        h = Harness()
        h.step(status(soc=30))
        self.assertEqual(["soc_critical"], h.step(status(soc=9)))
        self.assertEqual([], h.step(status(soc=8)))
        self.assertEqual([], h.step(status(soc=12)))


class ScheduleTests(unittest.TestCase):
    def test_outage_soon_at_60_and_15_minutes(self):
        h = Harness()
        start = T0 + timedelta(minutes=90)
        self.assertNotIn("outage_soon", h.step(status(next_start=start), now=T0))
        self.assertIn("outage_soon", h.step(status(next_start=start), now=start - timedelta(minutes=58)))
        self.assertNotIn("outage_soon", h.step(status(next_start=start), now=start - timedelta(minutes=40)))
        self.assertIn("outage_soon", h.step(status(next_start=start), now=start - timedelta(minutes=14)))
        self.assertNotIn("outage_soon", h.step(status(next_start=start), now=start - timedelta(minutes=5)))
        later = start + timedelta(hours=6)
        self.assertIn("outage_soon", h.step(status(next_start=later), now=later - timedelta(minutes=50)))

    def test_late_start_jumps_straight_to_15_min_alert_once(self):
        h = Harness()
        start = T0 + timedelta(minutes=10)
        self.assertEqual(1, h.step(status(next_start=start), now=T0).count("outage_soon"))
        self.assertNotIn("outage_soon", h.step(status(next_start=start), now=T0 + timedelta(minutes=2)))

    def test_reserve_not_enough_for_next_outage_once_per_window(self):
        h = Harness()
        start = T0 + timedelta(hours=3)
        self.assertIn("reserve_short_next", h.step(status(next_start=start, covers=False, runtime=3600)))
        self.assertNotIn("reserve_short_next", h.step(status(next_start=start, covers=False, runtime=3600)))

    def test_reserve_not_enough_during_outage(self):
        h = Harness()
        h.step(status(grid="on"))
        h.step(status(grid="off", offgrid=150.0, direction="discharging"))
        ids = h.step(status(grid="off", offgrid=150.0, direction="discharging", covers=False, runtime=1800,
                            current_end=T0 + timedelta(hours=3)))
        self.assertIn("reserve_short_current", ids)


class ChargingAndHealthTests(unittest.TestCase):
    def test_charging_started_not_repeated_within_an_hour(self):
        h = Harness()
        h.step(status(direction="idle"))
        self.assertIn("charging_started", h.step(status(direction="charging", power=800.0)))
        h.step(status(direction="idle"), now=T0 + timedelta(minutes=10))
        self.assertNotIn("charging_started", h.step(status(direction="charging"), now=T0 + timedelta(minutes=20)))
        h.step(status(direction="idle"), now=T0 + timedelta(minutes=70))
        self.assertIn("charging_started", h.step(status(direction="charging"), now=T0 + timedelta(minutes=80)))

    def test_charged_full_once(self):
        h = Harness()
        h.step(status(soc=99, direction="charging"))
        self.assertIn("charged_full", h.step(status(soc=100, direction="charging")))
        self.assertNotIn("charged_full", h.step(status(soc=100, direction="idle")))

    def test_temperature_high_with_hysteresis(self):
        h = Harness()
        h.step(status(temp=30.0))
        self.assertIn("temperature_high", h.step(status(temp=46.0)))
        self.assertNotIn("temperature_high", h.step(status(temp=44.0)))
        self.assertNotIn("temperature_high", h.step(status(temp=46.0)))
        h.step(status(temp=41.0))
        self.assertIn("temperature_high", h.step(status(temp=46.0)))

    def test_cold_charging(self):
        h = Harness()
        h.step(status(temp=2.0, direction="idle"))
        self.assertIn("temperature_cold_charging", h.step(status(temp=2.0, direction="charging")))


class ToggleTests(unittest.TestCase):
    def test_disabled_alert_is_not_posted_but_state_advances(self):
        h = Harness(enabled={**{i: True for i in alerts.ALERT_IDS}, "light_lost": False})
        h.step(status(grid="on"))
        self.assertEqual([], h.step(status(grid="off", offgrid=150.0)))
        self.assertIn("light_returned", h.step(status(grid="on")))

    def test_every_alert_has_ukrainian_label(self):
        for alert_id in alerts.ALERT_IDS:
            self.assertTrue(alerts.ALERT_LABELS[alert_id])


class MonitorTests(unittest.TestCase):
    def test_same_poll_is_evaluated_once(self):
        mon = alerts.AlertMonitor(lambda: {i: True for i in alerts.ALERT_IDS})
        st = status(soc=30)
        mon.observe(st, T0)
        low = status(soc=15)
        self.assertEqual(["soc_low"], [a.id for a in mon.observe(low, T0)])
        self.assertEqual([], mon.observe(low, T0))

    def test_failure_count_exposed(self):
        mon = alerts.AlertMonitor(lambda: {})
        mon.observe(status(), T0)
        mon.observe(status(ok=False), T0)
        mon.observe(status(ok=False), T0)
        self.assertEqual(2, mon.consecutive_failures)
        self.assertFalse(mon.is_stale)
        mon.observe(status(ok=False), T0)
        self.assertTrue(mon.is_stale)


if __name__ == "__main__":
    unittest.main()

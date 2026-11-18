"""UPS bypass: firmwares that report the backup-port load in offgrid_power while the grid feeds it.

Public report: «у режимі байпасу додаток все одно показує що немає світла». Our fw148 unit reads offgrid 0 W with the
grid, and during real outages ongrid is exactly 0 while bat_wh (≈ 5 Wh steps) falls with the load — those
sequences must still go OFF at once (auto mode, nothing learned). Synthetic sequences below, 37 s polls.
"""
import os
import tempfile
import unittest
from unittest.mock import patch

import gridstate
from config import load_settings, validate
from history import HistoryStore, outage_periods

T0 = 1_763_000_000.0
POLL = 37.0


def quantized(wh):
    return 5.0 * round(wh / 5.0)


def run(points, tracker=None, **kw):
    """points: (ts, offgrid, ongrid, bat_wh) → final tracker and [(ts, tracker, signal)]."""
    log = []
    for ts, off, on, wh in points:
        tracker, signal = gridstate.step(tracker, ts, off, on, wh, **kw)
        log.append((ts, tracker, signal))
    return tracker, log


def draw(start, minutes, watts, ongrid=0.0, wh0=4000.0, drains=True):
    """A backup draw of `watts` every 37 s; bat_wh falls with it (a real outage) or stays flat (bypass)."""
    count = int(minutes * 60 / POLL) + 1
    return [(start + i * POLL, watts, ongrid, quantized(wh0 - (watts * i * POLL / 3600.0 if drains else 0.0)))
            for i in range(count)]


def idle(start, minutes, wh=4000.0, ongrid=0.0):
    return [(start + i * POLL, 0.0, ongrid, wh) for i in range(int(minutes * 60 / POLL) + 1)]


class BypassRuleTests(unittest.TestCase):
    def test_grid_import_beats_backup_draw_and_is_learned(self):
        # this unit's bypass: the grid port imports what the backup port delivers (+ a little top-up)
        t, log = run(draw(T0, 3, 200.0, ongrid=-230.0, drains=False))
        self.assertEqual("on", log[0][1].state)
        self.assertEqual("grid_charging", log[0][1].confirmed_by)
        self.assertTrue(all(e[1].state == "on" for e in log))
        self.assertTrue(t.bypass_load_seen)
        self.assertTrue(t.bypass_active)
        self.assertFalse(any(e[2].lost for e in log))

    def test_grid_import_returns_the_light_during_a_draw(self):
        t, _ = run(draw(T0, 2, 300.0))                       # real outage first
        self.assertEqual("off", t.state)
        t, log = run(draw(T0 + 200, 1, 300.0, ongrid=-400.0, wh0=3900.0, drains=False), t)
        self.assertEqual("on", log[0][1].state)
        self.assertEqual(T0 + 200, log[0][2].returned_at)

    def test_flat_battery_while_backup_draws_is_bypass_after_30_wh(self):
        # ongrid 0 (full battery, nothing imported for itself), 200 W on the backup output, bat_wh flat
        points = draw(T0, 12, 200.0, drains=False)
        t, log = run(points)
        self.assertEqual("off", log[0][1].state)             # nothing learned yet: legacy immediate loss
        back = next(e for e in log if e[2].returned_at is not None)
        self.assertGreaterEqual(back[0] - T0, 9 * 60 - POLL)  # E = 200 W × 9 min = 30 Wh
        self.assertLess(back[0] - T0, 10 * 60)
        self.assertEqual(T0, back[2].returned_at)            # back-dated to the start of the window
        self.assertEqual("on", t.state)
        self.assertEqual("bypass_no_discharge", t.confirmed_by)
        self.assertTrue(t.bypass_load_seen)

    def test_bypass_timeline_is_back_dated_to_on(self):
        points = idle(T0 - 600, 9) + draw(T0, 12, 200.0, drains=False)
        rows = [{"ts": ts, "offgrid_w": off, "ongrid_w": on, "bat_wh": wh} for ts, off, on, wh in points]
        spans = gridstate.timeline(rows, start=T0, now=points[-1][0])
        self.assertEqual(["on"], [s["state"] for s in spans])

    def test_real_outage_ongrid_zero_and_falling_battery_stays_off(self):
        # 290 W ≈ 3 Wh per 37 s poll; bat_wh in 5 Wh steps — as on our fw148 unit
        t, log = run(idle(T0 - 400, 6) + draw(T0, 30, 290.0, wh0=3000.0))
        first = next(e for e in log if e[0] == T0)
        self.assertTrue(first[2].lost)
        self.assertEqual("off", first[1].state)
        after = [e for e in log if e[0] >= T0]
        self.assertTrue(all(e[1].state == "off" for e in after))
        self.assertFalse(any(e[2].returned_at for e in after))
        self.assertFalse(t.bypass_load_seen)
        self.assertEqual("backup_draw", t.lost_by)

    def test_partial_flat_battery_steps_do_not_fake_a_bypass(self):
        # quantized steps arrive late (a 5 Wh plateau at the start): still OFF
        points = [(ts, off, on, wh + 5.0 if i < 3 else wh) for i, (ts, off, on, wh) in
                  enumerate(draw(T0, 30, 160.0, wh0=5120.0))]
        t, log = run(points)
        self.assertTrue(all(e[1].state == "off" for e in log))

    def test_failed_poll_restarts_the_energy_window(self):
        points = draw(T0, 6, 200.0, drains=False) + [(T0 + 6 * 60 + 20, None, None, None)] + \
            draw(T0 + 6 * 60 + 40, 6, 200.0, drains=False)
        t, log = run(points)
        self.assertTrue(all(e[1].state == "off" for e in log))   # neither window reached 30 Wh

    def test_missing_bat_wh_keeps_the_legacy_rule(self):
        points = [(ts, off, on, None) for ts, off, on, _ in draw(T0, 20, 200.0, drains=False)]
        t, log = run(points)
        self.assertTrue(all(e[1].state == "off" for e in log))

    def test_long_gap_restarts_the_energy_window(self):
        a = draw(T0, 6, 200.0, drains=False)
        b = draw(a[-1][0] + 600, 6, 200.0, drains=False)
        t, log = run(a + b, confirm_gap=120.0)
        self.assertTrue(all(e[1].state == "off" for e in log))


class LearnedBypassTests(unittest.TestCase):
    def learned_on(self):
        t, _ = run(idle(T0 - 400, 6, ongrid=-60.0))
        self.assertEqual("on", t.state)
        return gridstate.replace(t, bypass_load_seen=True)

    def test_new_draw_is_pending_while_the_battery_is_flat(self):
        t, log = run(draw(T0, 8, 200.0, drains=False), self.learned_on())
        self.assertTrue(all(e[1].state == "on" for e in log))
        self.assertTrue(log[0][2].pending_loss)
        self.assertFalse(any(e[2].lost for e in log))

    def test_flat_draw_becomes_bypass_then_no_longer_pending(self):
        t, log = run(draw(T0, 15, 200.0, drains=False), self.learned_on())
        self.assertTrue(t.bypass_active)
        self.assertFalse(log[-1][2].pending_loss)
        self.assertEqual("on", t.state)

    def test_new_draw_goes_off_once_the_battery_visibly_discharges(self):
        t, log = run(draw(T0, 10, 290.0, wh0=3000.0), self.learned_on())
        lost = next(e for e in log if e[2].lost)
        self.assertLessEqual(lost[0] - T0, 3 * 60)            # D ≥ 10 Wh at 290 W ≈ 2 min
        self.assertEqual(T0, lost[2].lost_at)                  # back-dated to the start of the draw
        self.assertEqual("off", t.state)
        self.assertEqual("battery_discharge", t.lost_by)
        self.assertEqual(T0, t.off_since)
        self.assertTrue(all(e[1].state == "on" for e in log if e[0] < lost[0]))

    def test_outage_during_a_long_bypass_draw_is_detected(self):
        bypass = draw(T0, 60, 200.0, drains=False)
        outage = draw(bypass[-1][0] + POLL, 15, 200.0, wh0=4000.0)
        t, log = run(bypass + outage, self.learned_on())
        lost = next(e for e in log if e[2].lost)
        self.assertGreater(lost[0], bypass[-1][0])
        self.assertLess(lost[0] - bypass[-1][0], 8 * 60)
        self.assertEqual("off", t.state)

    def test_learned_flag_from_replay_seed(self):
        rows = [{"ts": ts, "offgrid_w": off, "ongrid_w": on, "bat_wh": wh}
                for ts, off, on, wh in idle(T0 - 400, 6, ongrid=-60.0) + draw(T0, 4, 200.0, drains=False)]
        self.assertEqual("off", gridstate.replay(rows).state)
        self.assertEqual("on", gridstate.replay(rows, bypass_seen=True).state)
        self.assertEqual("on", gridstate.replay(rows, bypass="yes").state)
        self.assertEqual("off", gridstate.replay(rows, bypass="no", bypass_seen=True).state)

    def test_learned_timeline_back_dates_the_loss(self):
        points = idle(T0 - 400, 6, ongrid=-60.0) + draw(T0, 10, 290.0, wh0=3000.0)
        rows = [{"ts": ts, "offgrid_w": off, "ongrid_w": on, "bat_wh": wh} for ts, off, on, wh in points]
        spans = gridstate.timeline(rows, start=T0 - 400, now=points[-1][0], bypass_seen=True)
        off = [s for s in spans if s["state"] == "off"]
        self.assertEqual(T0, off[0]["start"])


class ModeTests(unittest.TestCase):
    def test_no_keeps_legacy_immediate_loss_and_never_learns(self):
        t, log = run(draw(T0, 15, 200.0, ongrid=-230.0, drains=False), bypass="no")
        self.assertTrue(log[0][2].lost)
        self.assertTrue(all(e[1].state == "off" for e in log))
        self.assertFalse(t.bypass_load_seen)

    def test_yes_requires_battery_confirmation_without_learning(self):
        t, _ = run(idle(T0 - 400, 6, ongrid=-60.0))
        t, log = run(draw(T0, 5, 200.0, drains=False), t, bypass="yes")
        self.assertTrue(all(e[1].state == "on" for e in log))
        self.assertTrue(log[0][2].pending_loss)

    def test_mode_normalization(self):
        self.assertEqual("auto", gridstate.normalize_bypass_mode(None))
        self.assertEqual("yes", gridstate.normalize_bypass_mode("YES"))
        self.assertEqual("no", gridstate.normalize_bypass_mode("off"))
        self.assertEqual("auto", gridstate.normalize_bypass_mode("maybe"))

    def test_config_env(self):
        self.assertEqual("auto", load_settings({}).bypass_load)
        self.assertEqual("yes", load_settings({"MARSTEK_BYPASS_LOAD": "Yes"}).bypass_load)
        bad = validate(load_settings({"MARSTEK_HOST": "h", "MARSTEK_BYPASS_LOAD": "maybe"}))
        self.assertTrue(any("MARSTEK_BYPASS_LOAD" in e for e in bad))
        self.assertFalse(any("MARSTEK_BYPASS_LOAD" in e for e in validate(load_settings({"MARSTEK_HOST": "h"}))))

    def test_history_outage_periods_ignore_a_bypass_draw(self):
        points = idle(T0 - 600, 9) + draw(T0, 30, 200.0, drains=False)
        rows = [{"ts": ts, "offgrid_w": off, "ongrid_w": on, "bat_wh": wh} for ts, off, on, wh in points]
        self.assertEqual([], outage_periods(rows, now=points[-1][0]))
        self.assertEqual(1, len(outage_periods(rows, now=points[-1][0], bypass="no")))


class PollerBypassTests(unittest.TestCase):
    """End to end through the poller: grid_detail exposes the basis, the learned flag is persisted per device."""

    def setUp(self):
        from server import poller
        self.poller = poller
        self.tmp = tempfile.TemporaryDirectory()
        self.store = HistoryStore(os.path.join(self.tmp.name, "h.db"))
        self._reset()

    def tearDown(self):
        self._reset()  # never leak a learned flag into other poller tests
        self.store.close()
        self.tmp.cleanup()

    def _reset(self):
        p = self.poller
        p.detector, p.initial_journaled = None, False
        p.grid, p.grid_seeded, p.bypass_seen_cache = None, False, None
        p.load_stats = p.load_stats_at = None
        p.battery_samples.clear()

    def poll(self, ts, off, on, wh):
        def response(method):
            if method == "Bat.GetStatus":
                return {"ok": True, "data": {"soc": 100, "bat_capacity": wh, "rated_capacity": 5120.0, "bat_temp": 20}}
            if method == "ES.GetStatus":
                return {"ok": True, "data": {"offgrid_power": off, "ongrid_power": on, "bat_soc": 100}}
            if method == "ES.GetMode":
                return {"ok": True, "data": {"mode": "UPS"}}
            return {"ok": True, "data": {}}
        with patch.object(self.poller, "history", self.store), patch("server.time.sleep"), \
                patch.object(self.poller, "query", side_effect=response), patch("server.time.time", return_value=ts):
            self.poller.poll_once()
            return self.poller.snapshot()

    def test_bypass_is_exposed_and_persisted(self):
        for ts, off, on, wh in idle(T0 - 400, 6):
            snap = self.poll(ts, off, on, wh)
        for ts, off, on, wh in draw(T0, 12, 200.0, drains=False):
            snap = self.poll(ts, off, on, wh)
        detail = snap["power_outage"]["grid_detail"]
        self.assertEqual("on", snap["power_outage"]["grid_state"])
        self.assertTrue(detail["bypass_load_seen"])
        self.assertTrue(detail["bypass_now"])
        self.assertEqual("bypass_no_discharge", detail["confirmed_by"])
        self.assertEqual("auto", detail["bypass_mode"])
        self.assertIn("pending_return", detail)                 # backwards-compatible keys kept
        self.assertEqual("1", self.store.get_meta(self.poller._bypass_key()))
        # a restart re-seeds the learned flag from the DB: a new draw is pending, not «світла нема»
        self._reset()
        start = T0 + 3600
        for ts, off, on, wh in idle(start, 6):
            snap = self.poll(ts, off, on, wh)
        snap = self.poll(start + 400, 150.0, 0.0, 4000.0)
        self.assertEqual("on", snap["power_outage"]["grid_state"])
        self.assertTrue(snap["power_outage"]["grid_detail"]["pending_outage"])


if __name__ == "__main__":
    unittest.main()

"""Light-return hysteresis and overload-trip detection on the REAL fw148 UPS sequence (tests/fixtures/ups_ac_trip_fw148.json).

Times below are Europe/Kyiv (the fixture's `local_kyiv`); the same evening in CET is one hour earlier
(21:46:45 Kyiv = 20:46:45 CET). An inverter air conditioner on the backup output pauses its compressor (0 W) while
the grid is still off — before 1.3.7 every pause journaled a false «Світло повернулось».
"""
import json
import unittest
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import gridstate

KYIV = ZoneInfo("Europe/Kyiv")
SAMPLES = json.loads((Path(__file__).parent / "fixtures" / "ups_ac_trip_fw148.json").read_text(encoding="utf-8"))["samples"]


def hm(ts):
    return datetime.fromtimestamp(ts, KYIV).strftime("%H:%M:%S")


def at(hms):
    return next(s["ts"] for s in SAMPLES if s["local_kyiv"] == hms)


def run(samples, **kw):
    tracker, log = None, []
    for s in samples:
        tracker, signal = gridstate.step(tracker, s["ts"], s["offgrid_w"], s["ongrid_w"], **kw)
        log.append((s, tracker, signal))
    return tracker, log


class RealSequenceTests(unittest.TestCase):
    def setUp(self):
        self.tracker, self.log = run(SAMPLES)

    def test_light_lost_twice_returned_only_by_grid_charging(self):
        lost = [hm(s["ts"]) for s, _, sig in self.log if sig.lost]
        returned = [(hm(s["ts"]), hm(sig.returned_at)) for s, _, sig in self.log if sig.returned_at is not None]
        self.assertEqual(["21:46:45", "21:51:55"], lost)
        # one outage 21:46:45–21:51:18 (ongrid −1195 W = UPS top-up from the grid), the second from 21:51:55 until
        # the grid charges the battery again at 22:15:02 — no return during compressor pauses or after the trip
        self.assertEqual([("21:51:18", "21:51:18"), ("22:15:02", "22:15:02")], returned)

    def test_compressor_pauses_keep_the_light_off(self):
        for hms in ("21:48:40", "21:49:17", "21:50:41", "21:52:32", "21:55:03", "22:08:35", "22:10:57", "22:13:27"):
            entry = next(e for e in self.log if e[0]["local_kyiv"] == hms)
            self.assertEqual("off", entry[1].state, hms)
            self.assertTrue(entry[2].pending, hms)

    def test_overload_trip_detected_once_with_last_backup_watts(self):
        trips = [(hm(s["ts"]), sig.trip_w) for s, _, sig in self.log if sig.trip_w is not None]
        self.assertEqual([("22:08:35", 779.0)], trips)

    def test_trip_blocks_the_five_minute_idle_rule(self):
        entry = next(e for e in self.log if e[0]["local_kyiv"] == "22:13:27")
        self.assertTrue(entry[1].tripped)
        self.assertEqual("off", entry[1].state)

    def test_final_state_on_confirmed_by_grid_charging(self):
        self.assertEqual("on", self.tracker.state)
        self.assertEqual("grid_charging", self.tracker.confirmed_by)


class SyntheticRuleTests(unittest.TestCase):
    T0 = 1_800_000_000.0

    def rows(self, spec, step=15.0):
        """spec: list of (offgrid, ongrid) per poll, None offgrid = failed poll."""
        return [{"ts": self.T0 + i * step, "offgrid_w": o, "ongrid_w": g} for i, (o, g) in enumerate(spec)]

    def test_five_minutes_without_load_confirm_and_back_date_the_return(self):
        rows = self.rows([(0, 0)] * 25 + [(300, 0)] * 4 + [(0, 0)] * 22)
        tracker, log = run(rows)
        returned = [sig.returned_at for _, _, sig in log if sig.returned_at is not None]
        self.assertEqual([self.T0 + 29 * 15.0], returned)  # back-dated to the first 0 W poll of the run
        confirmed_at = next(s["ts"] for s, _, sig in log if sig.returned_at is not None)
        self.assertEqual(self.T0 + (29 + 20) * 15.0, confirmed_at)  # 300 s later
        self.assertEqual("idle_5min", tracker.confirmed_by)

    def test_failed_poll_restarts_the_idle_run(self):
        rows = self.rows([(0, 0)] * 25 + [(300, 0)] + [(0, 0)] * 15 + [(None, None)] + [(0, 0)] * 15)
        tracker, log = run(rows)
        self.assertEqual("off", tracker.state)
        self.assertFalse(any(sig.returned_at for _, _, sig in log[26:]))

    def test_long_silence_between_answers_restarts_the_idle_run(self):
        rows = self.rows([(0, 0)] * 25 + [(300, 0), (0, 0)])
        rows.append({"ts": rows[-1]["ts"] + 400, "offgrid_w": 0, "ongrid_w": 0})
        tracker, _ = run(rows)
        self.assertEqual("off", tracker.state)

    def test_grid_charging_confirms_immediately(self):
        tracker, log = run(self.rows([(0, 0)] * 25 + [(250, 0), (0, -1195)]))
        self.assertEqual("on", tracker.state)
        self.assertEqual(self.T0 + 26 * 15.0, log[-1][2].returned_at)

    def test_small_negative_ongrid_is_not_grid_charging(self):
        tracker, _ = run(self.rows([(0, 0)] * 25 + [(250, 0), (0, -14), (0, -40)]))
        self.assertEqual("off", tracker.state)

    def test_light_lost_is_immediate(self):
        tracker, log = run(self.rows([(0, 0)] * 25 + [(6, 0)]))
        self.assertTrue(log[-1][2].lost)
        self.assertEqual("off", tracker.state)

    def test_fast_rise_below_500_then_silence_is_a_trip(self):
        _, log = run(self.rows([(0, 0)] * 25 + [(150, 0), (320, 0), (480, 0), (None, None), (0, 0)]))
        self.assertEqual([480.0], [sig.trip_w for _, _, sig in log if sig.trip_w is not None])

    def test_load_stop_without_silence_is_not_a_trip(self):
        _, log = run(self.rows([(0, 0)] * 25 + [(900, 0), (0, 0)]))
        self.assertFalse(any(sig.trip_w for _, _, sig in log))

    def test_unknown_until_first_confirmation(self):
        tracker, _ = run(self.rows([(0, 0)] * 5))
        self.assertIsNone(tracker.state)

    def test_confirm_gap_follows_the_poll_interval(self):
        self.assertEqual(120.0, gridstate.confirm_gap_seconds(15))
        self.assertEqual(150.0, gridstate.confirm_gap_seconds(60))


class TimelineTests(unittest.TestCase):
    def test_real_evening_timeline(self):
        midnight = datetime(2026, 11, 11, tzinfo=KYIV).timestamp()
        now = at("22:16:16") + 10
        spans = gridstate.timeline(SAMPLES, start=midnight, now=now)
        view = [(hm(s["start"]), hm(s["end"]), s["state"]) for s in spans]
        # before the first poll and until 5 min of 0 W answers confirm the light (first poll 21:30:32): unknown
        self.assertEqual(("00:00:00", "21:36:06", "unknown"), view[0])
        self.assertEqual(("21:36:06", "21:46:45", "on"), view[1])
        self.assertEqual([("21:46:45", "21:51:18", "off"), ("21:51:18", "21:51:55", "on"), ("21:51:55", "22:15:02", "off"),
                          ("22:15:02", hm(now), "on")], view[-4:])
        for a, b in zip(spans, spans[1:]):
            self.assertAlmostEqual(a["end"], b["start"])
            self.assertNotEqual(a["state"], b["state"])

    def test_gaps_are_unknown_and_merged_intervals_do_not_bridge_them(self):
        t0 = 1_800_000_000.0
        rows = [{"ts": t0 + i * 15, "offgrid_w": 200, "ongrid_w": 0} for i in range(10)]
        rows += [{"ts": t0 + 3000 + i * 15, "offgrid_w": 200, "ongrid_w": 0} for i in range(10)]
        spans = gridstate.timeline(rows, start=t0, now=t0 + 3000 + 9 * 15)
        self.assertEqual(["off", "unknown", "off"], [s["state"] for s in spans])
        self.assertAlmostEqual(t0 + 135, spans[0]["end"])
        self.assertAlmostEqual(t0 + 3000, spans[2]["start"])

    def test_idle_return_is_back_dated_in_the_timeline(self):
        t0 = 1_800_000_000.0
        spec = [(0, 0)] * 25 + [(300, 0)] * 4 + [(0, 0)] * 30
        rows = [{"ts": t0 + i * 15, "offgrid_w": o, "ongrid_w": g} for i, (o, g) in enumerate(spec)]
        spans = gridstate.timeline(rows, start=t0, now=rows[-1]["ts"])
        off = [s for s in spans if s["state"] == "off"]
        self.assertEqual(1, len(off))
        self.assertAlmostEqual(t0 + 25 * 15, off[0]["start"])
        self.assertAlmostEqual(t0 + 29 * 15, off[0]["end"])

    def test_stale_last_poll_ends_in_unknown(self):
        t0 = 1_800_000_000.0
        rows = [{"ts": t0 + i * 15, "offgrid_w": 200, "ongrid_w": 0} for i in range(4)]
        spans = gridstate.timeline(rows, start=t0, now=t0 + 2000)
        self.assertEqual(["off", "unknown"], [s["state"] for s in spans])
        self.assertAlmostEqual(t0 + 45, spans[0]["end"])  # a stale last poll is not extended


if __name__ == "__main__":
    unittest.main()

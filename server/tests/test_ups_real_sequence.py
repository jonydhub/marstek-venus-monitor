"""End-to-end poller run over the REAL fw148 UPS evening (fixtures/ups_ac_trip_fw148.json), Kyiv time.

Journal, light state, overload-trip anomaly, leaving UPS mode and the smoothed runtime, as the server publishes them.
"""
import json
import os
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
from zoneinfo import ZoneInfo

import events as journal
from history import HistoryStore
from server import poller

KYIV = ZoneInfo("Europe/Kyiv")
SAMPLES = json.loads((Path(__file__).parent / "fixtures" / "ups_ac_trip_fw148.json").read_text(encoding="utf-8"))["samples"]
MODE_MANUAL_FROM = "22:10:57"  # after the trip ES.GetMode reported Manual although the native app showed UPS


def hm(ts):
    return datetime.fromtimestamp(ts, KYIV).strftime("%H:%M:%S")


def iso_hm(hms):
    """The API rounds timestamps to whole seconds: the ISO clock of the fixture poll at `hms`."""
    ts = next(s["ts"] for s in SAMPLES if s["local_kyiv"] == hms)
    return datetime.fromtimestamp(round(ts), KYIV).strftime("%H:%M:%S")


class RealUpsEveningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.store = HistoryStore(os.path.join(cls.tmp.name, "h.db"))
        poller.detector, poller.initial_journaled = None, False
        poller.grid, poller.grid_seeded = None, False
        poller.load_stats = poller.load_stats_at = None
        poller.battery_samples.clear()
        cls.snapshots = {}
        manual = False
        with patch.object(poller, "history", cls.store), patch("server.time.sleep"):
            for sample in SAMPLES:
                manual = manual or sample["local_kyiv"] == MODE_MANUAL_FROM

                def response(method, s=sample, manual=manual):
                    if method == "Bat.GetStatus":
                        return {"ok": True, "data": {"soc": s["soc"], "bat_capacity": s["bat_wh"], "rated_capacity": 5120.0,
                                                     "bat_temp": s["temp_c"]}}
                    if method == "ES.GetStatus":
                        if s["offgrid_w"] is None:
                            return {"ok": False, "error": "No response after retry"}
                        return {"ok": True, "data": {"offgrid_power": s["offgrid_w"], "ongrid_power": s["ongrid_w"],
                                                     "bat_soc": s["soc"]}}
                    if method == "ES.GetMode":
                        return {"ok": True, "data": {"mode": "Manual" if manual else "UPS"}}
                    return {"ok": True, "data": {}}

                with patch.object(poller, "query", side_effect=response), patch("server.time.time", return_value=sample["ts"]):
                    poller.poll_once()
                cls.snapshots[sample["local_kyiv"]] = poller.snapshot()
        cls.events = list(reversed(cls.store.events(limit=500)))

    @classmethod
    def tearDownClass(cls):
        cls.store.close()
        cls.tmp.cleanup()

    def kinds_at(self, kind):
        return [hm(e["ts"]) for e in self.events if e["kind"] == kind]

    def test_one_outage_returned_by_charging_then_a_second_one_without_false_returns(self):
        self.assertEqual(["21:46:45", "21:51:55"], self.kinds_at("light_lost"))
        self.assertEqual(["21:51:18", "22:15:02"], self.kinds_at("light_returned"))
        first = next(e for e in self.events if e["kind"] == "light_returned")
        self.assertIn("після 5 хв (заряджання від мережі)", first["text"])

    def test_overload_trip_anomaly_once_with_the_last_backup_reading(self):
        trips = [e for e in self.events if e["kind"] == "anomaly_overload_trip"]
        self.assertEqual(["22:08:35"], [hm(e["ts"]) for e in trips])
        self.assertEqual(journal.overload_trip_text(779), trips[0]["text"])
        self.assertEqual("Можливо, спрацював захист станції від перевантаження (резервний вихід: останнє значення 779 Вт). "
                         "Код помилки дивіться в застосунку Marstek; якщо там 405 — зменшіть пускове навантаження.",
                         trips[0]["text"])
        self.assertEqual("anomaly", trips[0]["category"])

    def test_leaving_ups_mode_is_an_anomaly(self):
        left = [e for e in self.events if e["kind"] == "mode_left_ups"]
        self.assertEqual([MODE_MANUAL_FROM], [hm(e["ts"]) for e in left])
        self.assertEqual("Станція вийшла з режиму UPS (зараз: Manual)", left[0]["text"])

    def test_light_stays_off_during_compressor_pauses_and_after_the_trip(self):
        for hms in ("21:48:40", "21:52:32", "21:55:03", "22:08:35", "22:13:27"):
            outage = self.snapshots[hms]["power_outage"]
            self.assertEqual("off", outage["grid_state"], hms)
            self.assertTrue(outage["grid_detail"]["pending_return"], hms)
        detail = self.snapshots["22:13:27"]["power_outage"]["grid_detail"]
        self.assertEqual({"last_w": 779, "at": detail["overload_trip"]["at"]}, detail["overload_trip"])
        self.assertEqual(iso_hm("22:08:35"), detail["overload_trip"]["at"][11:19])

    def test_failed_poll_is_unknown_not_light_returned(self):
        self.assertEqual("unknown", self.snapshots["22:07:50"]["power_outage"]["grid_state"])

    def test_runtime_uses_the_ten_minute_energy_weighted_mean(self):
        autonomy = self.snapshots["22:07:01"]["power_outage"]["autonomy"]
        self.assertEqual("measured_backup_avg_10min", autonomy["load_basis"])
        self.assertTrue(300 < autonomy["load_w"] < 400, autonomy["load_w"])  # 22:00–22:07 averages ≈ 330 W, not 779
        paused = self.snapshots["21:55:03"]["power_outage"]["autonomy"]
        self.assertEqual("measured_backup_avg_10min", paused["load_basis"])  # 0 W now, but the outage still draws
        self.assertGreater(paused["load_w"], 5)

    def test_today_timeline_and_energy(self):
        today = self.snapshots["22:16:16"]["insights"]["today"]
        off = [(s["start"][11:19], s["end"][11:19]) for s in today["grid_timeline"] if s["state"] == "off"]
        self.assertEqual([(iso_hm("21:46:45"), iso_hm("21:51:18")), (iso_hm("21:51:55"), iso_hm("22:15:02"))], off)
        self.assertGreater(today["discharge_wh"], 50)
        self.assertGreater(today["charge_wh"], 30)
        self.assertAlmostEqual(today["outage_seconds"], (273 + 1387), delta=2)

    def test_last_grid_charge_is_the_ups_top_up(self):
        charge = self.snapshots["22:16:16"]["insights"]["last_grid_charge"]
        self.assertEqual(1195, charge["w"])
        self.assertEqual("2026-11-11T" + iso_hm("22:16:16"), charge["t"][:19])


if __name__ == "__main__":
    unittest.main()

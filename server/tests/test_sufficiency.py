"""sufficiency.py — «Чи вистачить» + charge advice; same cases as Android SufficiencyTest."""
import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from sufficiency import from_status, sufficiency

KYIV = ZoneInfo("Europe/Kyiv")


def ts(h, m=0, day=26):
    return datetime(2026, 11, day, h, m, tzinfo=KYIV).timestamp()


BASE = {"stored_wh": 4014, "rated_wh": 5120, "floor_pct": 11}  # floor 563,2 Wh → usable 3450,8


def _clean(value):
    return value.replace("\u00a0", " ") if isinstance(value, str) else value


def suff(**kw) -> dict:
    r = sufficiency(**dict(BASE, **kw))
    if r["verdict"]:
        r["verdict"] = {**r["verdict"], "text": _clean(r["verdict"]["text"])}
    r["advice"] = _clean(r["advice"])
    return r


class SufficiencyTests(unittest.TestCase):
    def test_outage_enough(self):
        r = suff(now=ts(17), light="off", load_w=975, current_end=ts(20))
        self.assertEqual(r["verdict"], {"ok": True, "text": "Вистачить до світла о 20:00 · запас ≈ 32 хв"})
        self.assertIsNone(r["advice"])

    def test_outage_short(self):
        r = suff(now=ts(17), light="off", load_w=1500, current_end=ts(20))
        # 3450,8 Wh / 1500 W = 2 год 18 хв → до 19:18, бракує 42 хв
        self.assertEqual(r["verdict"], {"ok": False, "text": "Не вистачить ≈ 42 хв: заряду до 19:18, світло о 20:00"})

    def test_outage_ends_tomorrow(self):
        r = suff(now=ts(22), light="off", load_w=300, current_end=ts(2, day=27))
        self.assertEqual(r["verdict"]["text"], "Вистачить до світла завтра о 02:00 · запас ≈ 7 год 30 хв")

    def test_outage_without_end_or_load(self):
        self.assertIsNone(suff(now=ts(17), light="off", load_w=975)["verdict"])
        self.assertIsNone(suff(now=ts(17), light="off", current_end=ts(20))["verdict"])

    def test_idle_shows_nothing(self):
        # light on, battery idle (100 % or not) — no forecast, even with an outage ahead
        self.assertEqual(suff(now=ts(18), light="on", load_w=494, stored_wh=5120, next_start=ts(21, day=27), next_end=ts(0, day=28)),
                         {"verdict": None, "advice": None})
        self.assertEqual(suff(now=ts(15), light="on", load_w=1000, stored_wh=2000, next_start=ts(20)),
                         {"verdict": None, "advice": None})

    def test_charging_full_in_time(self):
        # 1120 Wh at 1194 W = 56 min → 15:56, outage at 16:00
        r = suff(now=ts(15), light="on", charge_w=1194, stored_wh=4000, next_start=ts(16), next_end=ts(20))
        self.assertEqual(r, {"verdict": {"ok": True, "text": "Встигне зарядитися: 100 % о 15:56, відключення о 16:00"},
                             "advice": None})

    def test_charging_full_before_tomorrows_outage(self):
        r = suff(now=ts(18), light="on", charge_w=1200, stored_wh=2000, next_start=ts(21, day=27))
        self.assertEqual(r["verdict"]["text"], "Встигне зарядитися: 100 % о 20:36, відключення завтра о 21:00")

    def test_not_full_even_at_max(self):
        # 30 min at 1200 W: 2600 Wh → 51 %; at 2500 W: 3250 → 63 %
        r = suff(now=ts(15), light="on", charge_w=1200, stored_wh=2000, next_start=ts(15, 30), next_end=ts(19, 30))
        self.assertEqual(r["verdict"], {"ok": False, "text": "Не встигне зарядитися: до відключення о 15:30 буде ≈ 51 %"})
        self.assertEqual(r["advice"], "На 2,5 кВт — до 63 %: збільште потужність заряду в застосунку Marstek.")

    def test_full_at_max(self):
        # 2 h at 1000 W: 5000 → 98 %; at 2500 W full in 50 min 53 s → 15:51
        r = suff(now=ts(15), light="on", charge_w=1000, stored_wh=3000, next_start=ts(17), next_end=ts(19))
        self.assertEqual(r["verdict"]["text"], "Не встигне зарядитися: до відключення о 17:00 буде ≈ 98 %")
        self.assertEqual(r["advice"], "На 2,5 кВт — 100 % о 15:51: збільште потужність заряду в застосунку Marstek.")

    def test_almost_full_at_max_is_not_100(self):
        # 1 h at 1194 W → 74 %; at 2500 W: 5100 Wh = 99,6 % — not full → «до 99 %»
        r = suff(now=ts(15), light="on", charge_w=1194, stored_wh=2600, next_start=ts(16), next_end=ts(20))
        self.assertEqual(r["verdict"]["text"], "Не встигне зарядитися: до відключення о 16:00 буде ≈ 74 %")
        self.assertEqual(r["advice"], "На 2,5 кВт — до 99 %: збільште потужність заряду в застосунку Marstek.")

    def test_no_advice_at_full_power_and_nothing_without_outage_or_when_full(self):
        r = suff(now=ts(15), light="on", charge_w=2450, stored_wh=1000, next_start=ts(15, 30))
        self.assertEqual(r["verdict"]["text"], "Не встигне зарядитися: до відключення о 15:30 буде ≈ 43 %")
        self.assertIsNone(r["advice"])
        self.assertIsNone(suff(now=ts(15), light="on", charge_w=1200, stored_wh=1000)["verdict"])
        self.assertIsNone(suff(now=ts(15), light="on", charge_w=100, stored_wh=5080, next_start=ts(16))["verdict"])

    def test_from_status(self):
        status = {
            "power_outage": {"autonomy": {"stored_wh": 2600, "floor_pct": 11, "load_w": 457},
                             "next_outage": {"start": "2026-11-26T16:00:00+02:00", "end": "2026-11-26T20:00:00+02:00"}},
            "battery_flow": {"available": True, "direction": "charging", "power_w": 1194},
            "methods": {"Bat.GetStatus": {"data": {"rated_capacity": 5120, "bat_capacity": 2600}}},
        }
        r = from_status(status, ts(15), "on")
        self.assertEqual(r["verdict"]["text"].replace("\u00a0", " "), "Не встигне зарядитися: до відключення о 16:00 буде ≈ 74 %")
        self.assertTrue(r["advice"].startswith("На 2,5"))


if __name__ == "__main__":
    unittest.main()

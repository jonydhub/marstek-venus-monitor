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

    def test_next_outage_enough(self):
        r = suff(now=ts(15), light="on", load_w=457, next_start=ts(20), next_end=ts(0, day=27))
        # need 457 × 4 = 1828 Wh; left 4014 − 1828 = 2186 → 43 %
        self.assertEqual(r["verdict"], {"ok": True, "text": "На відключення 20:00–00:00 вистачить · лишиться ≈ 43 %"})

    def test_next_outage_tomorrow_names_the_day(self):
        r = suff(now=ts(18), light="on", load_w=457, next_start=ts(21, day=27), next_end=ts(0, day=28))
        self.assertEqual(r["verdict"]["text"], "На відключення завтра 21:00–00:00 вистачить · лишиться ≈ 52 %")

    def test_next_outage_short(self):
        r = suff(now=ts(15), light="on", load_w=1000, next_start=ts(20), next_end=ts(0, day=27))
        # need 4000, usable 3450,8 → бракує 549,2 Wh = 33 хв при 1000 Вт
        self.assertEqual(r["verdict"], {"ok": False, "text": "На відключення 20:00–00:00 не вистачить ≈ 33 хв"})

    def test_charging_counts_until_the_outage(self):
        # 1 h at 1194 W before 16:00 → 5120 (capped) → 4 h at 1000 W fits, 1120 left = 22 %
        r = suff(now=ts(15), light="on", load_w=1000, charge_w=1194, stored_wh=4000, next_start=ts(16), next_end=ts(20))
        self.assertEqual(r["verdict"], {"ok": True, "text": "На відключення 16:00–20:00 вистачить · лишиться ≈ 22 %"})
        self.assertIsNone(r["advice"])

    def test_advice_not_full_even_at_max(self):
        # 30 min at 1200 W: 2000 + 600 = 2600 → 51 %; at 2500 W: 2000 + 1250 → 63 %
        r = suff(now=ts(15), light="on", charge_w=1200, stored_wh=2000, next_start=ts(15, 30), next_end=ts(19, 30))
        self.assertEqual(r["advice"], "До відключення зарядиться лише до 51 %. На 2,5 кВт — до 63 %: "
                                      "збільште потужність заряду в застосунку Marstek.")

    def test_advice_reaches_full_at_max(self):
        # 2 h to the outage at 1000 W: 3000 + 2000 = 5000 → 98 %; at 2500 W full in 50 min 53 s → 15:51
        r = suff(now=ts(15), light="on", charge_w=1000, stored_wh=3000, next_start=ts(17), next_end=ts(19))
        self.assertEqual(r["advice"], "До відключення зарядиться лише до 98 %. На 2,5 кВт — 100 % о 15:51: "
                                      "збільште потужність заряду в застосунку Marstek.")

    def test_advice_almost_full_is_not_100(self):
        # 1 h at 1194 W: 2600 + 1194 → 74 %; at 2500 W: 5100 Wh = 99,6 % — not full → «до 99 %»
        r = suff(now=ts(15), light="on", charge_w=1194, stored_wh=2600, next_start=ts(16), next_end=ts(20))
        self.assertEqual(r["advice"], "До відключення зарядиться лише до 74 %. На 2,5 кВт — до 99 %: "
                                      "збільште потужність заряду в застосунку Marstek.")

    def test_no_advice(self):
        # fast enough / already near full power / not charging / no outage ahead
        self.assertIsNone(suff(now=ts(15), light="on", charge_w=1200, stored_wh=4500, next_start=ts(17))["advice"])
        self.assertIsNone(suff(now=ts(15), light="on", charge_w=2450, stored_wh=1000, next_start=ts(15, 30))["advice"])
        self.assertIsNone(suff(now=ts(15), light="on", stored_wh=1000, next_start=ts(15, 30))["advice"])
        self.assertIsNone(suff(now=ts(15), light="on", charge_w=1200, stored_wh=1000)["advice"])

    def test_from_status(self):
        status = {
            "power_outage": {"autonomy": {"stored_wh": 2600, "floor_pct": 11, "load_w": 457},
                             "next_outage": {"start": "2026-11-26T16:00:00+02:00", "end": "2026-11-26T20:00:00+02:00"}},
            "battery_flow": {"available": True, "direction": "charging", "power_w": 1194},
            "methods": {"Bat.GetStatus": {"data": {"rated_capacity": 5120, "bat_capacity": 2600}}},
        }
        r = from_status(status, ts(15), "on")
        self.assertTrue(r["verdict"]["ok"])
        self.assertTrue(r["advice"].startswith("До відключення зарядиться лише до 74"))


if __name__ == "__main__":
    unittest.main()

"""sufficiency() in app.js — «Чи вистачить» + charge advice; same cases as Android SufficiencyTest."""
import json
import re
import shutil
import subprocess
import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"
KYIV = ZoneInfo("Europe/Kyiv")


def ms(h, m=0, day=26):
    return int(datetime(2026, 11, day, h, m, tzinfo=KYIV).timestamp() * 1000)


BASE = {"stored_wh": 4014, "rated_wh": 5120, "floor_pct": 11}  # floor 563,2 Wh → usable 3450,8


def suff(**kw):
    src = re.search(r"const MAX_CHARGE_W.*?;\n", SCRIPT).group(0)  # type: ignore[union-attr]
    for name in ("spanText", "suffClock", "suffHm", "sufficiency"):
        src += re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S).group(0)  # type: ignore[union-attr]
    arg = json.dumps(dict(BASE, **kw))
    out = subprocess.run([NODE, "-e", src + f"process.stdout.write(JSON.stringify(sufficiency({arg})));"],
                         capture_output=True, text=True, encoding="utf-8", timeout=20, check=True).stdout
    return json.loads(out.replace("\\u00a0", " ").replace("\u00a0", " "))


@unittest.skipUnless(shutil.which("node"), "node not installed")
class SufficiencyTests(unittest.TestCase):
    def test_outage_enough(self):
        r = suff(now=ms(17), light="off", load_w=975, current_end=ms(20))
        self.assertEqual(r["verdict"], {"ok": True, "text": "Вистачить до світла о 20:00 · запас ≈ 32 хв"})
        self.assertIsNone(r["advice"])

    def test_outage_short(self):
        r = suff(now=ms(17), light="off", load_w=1500, current_end=ms(20))
        # 3450,8 Wh / 1500 W = 2 год 18 хв → до 19:18, бракує 42 хв
        self.assertEqual(r["verdict"], {"ok": False, "text": "Не вистачить ≈ 42 хв: заряду до 19:18, світло о 20:00"})

    def test_outage_ends_tomorrow(self):
        r = suff(now=ms(22), light="off", load_w=300, current_end=ms(2, day=27))
        self.assertEqual(r["verdict"]["text"], "Вистачить до світла завтра о 02:00 · запас ≈ 7 год 30 хв")

    def test_outage_without_end_or_load(self):
        self.assertIsNone(suff(now=ms(17), light="off", load_w=975)["verdict"])
        self.assertIsNone(suff(now=ms(17), light="off", current_end=ms(20))["verdict"])

    def test_next_outage_enough(self):
        r = suff(now=ms(15), light="on", load_w=457, next_start=ms(20), next_end=ms(24 - 24, day=27))
        # need 457 × 4 = 1828 Wh; left 4014 − 1828 = 2186 → 43 %
        self.assertEqual(r["verdict"], {"ok": True, "text": "На відключення 20:00–00:00 вистачить · лишиться ≈ 43 %"})

    def test_next_outage_short(self):
        r = suff(now=ms(15), light="on", load_w=1000, next_start=ms(20), next_end=ms(24 - 24, day=27))
        # need 4000, usable 3450,8 → бракує 549,2 Wh = 33 хв при 1000 Вт
        self.assertEqual(r["verdict"], {"ok": False, "text": "На відключення 20:00–00:00 не вистачить ≈ 33 хв"})

    def test_charging_counts_until_the_outage(self):
        # 1 h at 1194 W before 16:00 → stored 5120 (capped) → 4 h at 1000 W fits
        r = suff(now=ms(15), light="on", load_w=1000, charge_w=1194, stored_wh=4000, next_start=ms(16), next_end=ms(20))
        self.assertEqual(r["verdict"], {"ok": True, "text": "На відключення 16:00–20:00 вистачить · лишиться ≈ 22 %"})
        self.assertIsNone(r["advice"])

    def test_advice_slow_charge_full_at_max(self):
        # 30 min at 1200 W: 2000 + 600 = 2600 → 51 %; at 2500 W: full in 1 h 15 min > 30 min → 2000 + 1250 → 63 %
        r = suff(now=ms(15), light="on", charge_w=1200, stored_wh=2000, next_start=ms(15, 30), next_end=ms(19, 30))
        self.assertEqual(r["advice"], "До відключення о 15:30 зарядиться лише до 51 %. На 2,5 кВт — до 63 %: "
                                      "збільште потужність заряду в застосунку Marstek.")

    def test_advice_reaches_full_at_max(self):
        # 2 h to the outage, 1000 W → 4000 + 2000 → capped? no: 3000 + 2000 = 5000 → 98 %; at 2500: full in 51 min → 15:51
        r = suff(now=ms(15), light="on", charge_w=1000, stored_wh=3000, next_start=ms(17), next_end=ms(19))
        self.assertEqual(r["advice"], "До відключення о 17:00 зарядиться лише до 98 %. На 2,5 кВт — 100 % о 15:51: "
                                      "збільште потужність заряду в застосунку Marstek.")

    def test_no_advice(self):
        # fast enough / already at full power / not charging / no outage ahead
        self.assertIsNone(suff(now=ms(15), light="on", charge_w=1200, stored_wh=4500, next_start=ms(17))["advice"])
        self.assertIsNone(suff(now=ms(15), light="on", charge_w=2450, stored_wh=1000, next_start=ms(15, 30))["advice"])
        self.assertIsNone(suff(now=ms(15), light="on", stored_wh=1000, next_start=ms(15, 30))["advice"])
        self.assertIsNone(suff(now=ms(15), light="on", charge_w=1200, stored_wh=1000)["advice"])


if __name__ == "__main__":
    unittest.main()

"""Web texts of «Статистика» and the «Фактично» energy line (app.js under node); same cases as Android StatsTextsTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"
NAMES = ("formatKwh", "spanText", "kyivHm", "shortDay", "kwhNumber", "outagesWord", "statsOutageLines", "statsChargeLines",
         "statsDayText", "actualTotals")


def source():
    parts = []
    for name in NAMES:
        match = re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S) or re.search(rf"function {name}\(.*?}}\n", SCRIPT)
        assert match, f"{name} not found in app.js"
        parts.append(match.group(0))
    return "".join(parts)


def js(expr):
    code = source() + f"process.stdout.write(JSON.stringify({expr}));"
    return json.loads(subprocess.run([NODE, "-e", code], capture_output=True, text=True, encoding="utf-8", timeout=20,
                                     check=True).stdout)


OUTAGE = {"start": "2026-11-20T21:06:00+02:00", "end": "2026-11-21T00:15:00+02:00", "seconds": 11340, "energy_wh": 595.9,
          "avg_w": 190, "soc_start": 100, "soc_end": 86, "ongoing": False}
CHARGE = {"start": "2026-11-21T16:35:00+02:00", "end": "2026-11-21T17:01:00+02:00", "seconds": 1560, "energy_wh": 476.1,
          "avg_w": 1095, "soc_start": 92, "soc_end": 100, "estimated": False, "ongoing": False}


@unittest.skipUnless(shutil.which("node"), "node not installed")
class StatsTextTests(unittest.TestCase):
    def test_outage_lines(self):
        self.assertEqual(js(f"statsOutageLines({json.dumps(OUTAGE)})"),
                         ["Пт 20.11 · 21:06–00:15 · 3 год 9 хв", "0,60 кВт·год зі станції · ≈190 Вт · заряд 100 → 86 %"])

    def test_ongoing_outage(self):
        o = dict(OUTAGE, start="2026-11-21T15:58:00+02:00", seconds=2220, ongoing=True, avg_w=None, soc_end=None)
        self.assertEqual(js(f"statsOutageLines({json.dumps(o)})"), ["Сб 21.11 · з 15:58 · 37 хв · триває", "0,60 кВт·год зі станції"])

    def test_charge_lines(self):
        self.assertEqual(js(f"statsChargeLines({json.dumps(CHARGE)})"),
                         ["Сб 21.11 · 16:35–17:01 · 26 хв", "+0,48 кВт·год · ≈1095 Вт · 92 → 100 %"])
        self.assertEqual(js(f"statsChargeLines({json.dumps(dict(CHARGE, estimated=True))})")[1][:2], "≈ ")

    def test_day_text(self):
        self.assertEqual(js('statsDayText({off_seconds: 11340, outage_energy_wh: 595.9, charge_wh: 1631.7})'),
                         "без світла 3 год 9 хв · зі станції 0,60 · заряд 1,63 кВт·год")
        self.assertEqual(js('statsDayText({off_seconds: 0, outage_energy_wh: 0, charge_wh: 966.5})'), "без відключень · заряд 0,97 кВт·год")

    def test_plural(self):
        self.assertEqual(js("[0, 1, 2, 4, 5, 11, 12, 21, 25].map(outagesWord)"),
                         ["відключень", "відключення", "відключення", "відключення", "відключень", "відключень", "відключень",
                          "відключення", "відключень"])

    def test_actual_totals(self):
        self.assertEqual(js("actualTotals([{seconds: 900, energy: 35.2}, {seconds: 2199, energy: 346.9}])"),
                         "Без світла 52 хв · зі станції 0,38 кВт·год")
        self.assertEqual(js("actualTotals([{seconds: 600, energy: 0}])"), "Без світла 10 хв")
        self.assertEqual(js("actualTotals([])"), "")


if __name__ == "__main__":
    unittest.main()

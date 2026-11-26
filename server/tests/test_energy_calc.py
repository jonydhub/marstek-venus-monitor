"""energyCalc() in app.js — «Калькулятор»; same cases as Android EnergyCalcTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"
BATTERY = {"rated_wh": 5120, "stored_wh": 4014, "usable_wh": 3451, "floor_pct": 11}


def calc(watts, seconds, battery=None, tariff: object = "default"):
    src = "".join(re.search(rf"function {n}\(.*?\n}}\n", SCRIPT, re.S).group(0) for n in ("spanText", "energyCalc"))
    src += re.search(r"function calcNum\(.*?}\n", SCRIPT).group(0)
    src += re.search(r"const CALC_TARIFF.*?;\n", SCRIPT).group(0)
    args = f"{json.dumps(watts)}, {json.dumps(seconds)}, {json.dumps(battery)}" + ("" if tariff == "default" else f", {json.dumps(tariff)}")
    out = subprocess.run([NODE, "-e", src + f"process.stdout.write(JSON.stringify(energyCalc({args})));"],
                         capture_output=True, text=True, encoding="utf-8", timeout=20, check=True).stdout
    return json.loads(out.replace("\\u00a0", " ").replace("\u00a0", " "))


@unittest.skipUnless(shutil.which("node"), "node not installed")
class EnergyCalcTests(unittest.TestCase):
    def test_kettle_half_hour(self):
        r = calc(1500, 1800, BATTERY)
        self.assertEqual(r, {"energy": "750 Вт·год", "cost": "3,24 грн", "battery_share": "15 %", "soc_after": "64 %",
                             "fits": True, "runtime": "2 год 18 хв", "runtime_to": "до 11 %", "over_limit": False})

    def test_does_not_fit(self):
        r = calc(2000, 3 * 3600, BATTERY)
        self.assertEqual((r["energy"], r["cost"], r["battery_share"], r["fits"], r["soc_after"], r["runtime"]),
                         ("6,00 кВт·год", "25,92 грн", "117 %", False, None, "1 год 44 хв"))

    def test_over_backup_limit(self):
        r = calc(3000, 600, BATTERY)
        self.assertEqual((r["energy"], r["over_limit"]), ("500 Вт·год", True))

    def test_without_battery_data(self):
        r = calc(15, 24 * 3600)
        self.assertEqual((r["energy"], r["cost"], r["battery_share"], r["fits"], r["runtime"], r["runtime_to"]),
                         ("360 Вт·год", "1,56 грн", None, None, None, "до 0 %"))

    def test_usable_from_stored(self):
        self.assertEqual(calc(1500, 1800, dict(BATTERY, usable_wh=None))["runtime"], "2 год 18 хв")

    def test_empty_inputs(self):
        self.assertIsNone(calc(0, 1800, BATTERY))
        self.assertIsNone(calc(1500, 0, BATTERY))
        self.assertIsNone(calc(None, 1800, BATTERY))
        self.assertIsNone(calc(1500, 1800, BATTERY, tariff=0)["cost"])


if __name__ == "__main__":
    unittest.main()

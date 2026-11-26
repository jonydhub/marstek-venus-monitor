"""«Лічильники» in app.js (countersRows / countersNote); same cases as Android CountersTableTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"
C = {"since": "2026-11-20", "window_days": 7,
     "today": {"charge_wh": 843.9, "discharge_wh": 1718.5, "outage_energy_wh": 1462.4, "off_seconds": 9387, "outages": 2},
     "window": {"charge_wh": 8934.2, "discharge_wh": 5242.6, "outage_energy_wh": 3659.6, "off_seconds": 51127, "outages": 9},
     "total": {"charge_wh": 12034.2, "discharge_wh": 2.0, "outage_energy_wh": 0, "off_seconds": 30, "outages": 9}}


def js(expr):
    src = re.search(r"function kwhNumber\(.*?}\n", SCRIPT).group(0)  # type: ignore[union-attr]
    src += re.search(r"const COUNTER_ROWS.*?;\n", SCRIPT).group(0)  # type: ignore[union-attr]
    for name in ("counterCell", "countersRows", "countersNote"):
        src += re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S).group(0)  # type: ignore[union-attr]
    out = subprocess.run([NODE, "-e", src + f"process.stdout.write(JSON.stringify({expr}));"],
                         capture_output=True, text=True, encoding="utf-8", timeout=20, check=True).stdout
    return json.loads(out.replace("\\u00a0", " ").replace("\u00a0", " "))


@unittest.skipUnless(shutil.which("node"), "node not installed")
class CountersWebTests(unittest.TestCase):
    def test_rows(self):
        t = js(f"countersRows({json.dumps(C)})")
        self.assertEqual(t["head"], ["", "Сьогодні", "7 днів", "Усього"])
        self.assertEqual(t["rows"], [
            ["Заряджено", "0,84", "8,93", "12,03"],
            ["Розряджено", "1,72", "5,24", "—"],
            ["Спожито без світла", "1,46", "3,66", "—"],
            ["Без світла, год", "2,6", "14,2", "—"],
        ])

    def test_short_window(self):
        self.assertEqual(js(f"countersRows({json.dumps(dict(C, window_days=2))})")["head"][2], "2 дні")

    def test_note_and_missing(self):
        self.assertTrue(js(f"countersNote({json.dumps(C)})").startswith("Усього — з 20.11.2026"))
        self.assertIsNone(js("countersRows(null)"))
        self.assertIsNone(js("countersRows({today: {}})"))


if __name__ == "__main__":
    unittest.main()

"""«Статистика» tables in app.js (statsDayCells / statsGroups); same cases as Android StatsTableTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"
NAMES = ("kyivHm", "spanText", "kwhNumber", "weekDayTitle", "statsKwh", "statsSoc", "statsDayCells", "statsGroups")
TODAY = "2026-11-26T12:00:00+02:00"


def js(expr):
    parts = []
    for name in NAMES:
        match = re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S) or re.search(rf"function {name}\(.*?}}\n", SCRIPT)
        assert match, f"{name} not found in app.js"
        parts.append(match.group(0))
    # freeze «today» to 26.11.2026 (Kyiv) for weekDayTitle / statsGroups
    clock = f"const _RealDate = Date; Date = class extends _RealDate {{ constructor(...a) {{ super(...(a.length ? a : [{json.dumps(TODAY)}])); }} static now() {{ return new _RealDate({json.dumps(TODAY)}).getTime(); }} }};"
    code = clock + "".join(parts) + f"process.stdout.write(JSON.stringify({expr}));"
    out = subprocess.run([NODE, "-e", code], capture_output=True, text=True, encoding="utf-8", timeout=20, check=True).stdout
    return json.loads(out.replace("\\u00a0", " ").replace("\u00a0", " "))


def o(a, b, s, wh, s1, s2, on=False):
    return {"start": a, "end": b, "seconds": s, "energy_wh": wh, "avg_w": 100, "soc_start": s1, "soc_end": s2, "ongoing": on}


@unittest.skipUnless(shutil.which("node"), "node not installed")
class StatsTableTests(unittest.TestCase):
    def test_day_cells(self):
        self.assertEqual(js('statsDayCells({date: "2026-11-26", is_today: true, off_seconds: 4560, outage_energy_wh: 720.4, charge_wh: 840})'),
                         {"cells": ["Сьогодні", "1 год 16 хв", "0,72", "0,84"], "highlight": True})
        self.assertEqual(js('statsDayCells({date: "2026-11-23", off_seconds: 0, outage_energy_wh: 0, charge_wh: 966.5})'),
                         {"cells": ["Пн, 23.11", "—", "—", "0,97"], "highlight": False})
        self.assertEqual(js('statsDayCells({date: "2026-11-20", off_seconds: 25860, outage_energy_wh: 1220, charge_wh: 1631.7})')["cells"],
                         ["Пт, 20.11", "7 год 11 хв", "1,22", "1,63"])

    def test_outages_grouped_by_day(self):
        items = [
            o("2026-11-26T17:06:00+02:00", "2026-11-26T18:05:00+02:00", 3540, 560, 99, 87, on=True),
            o("2026-11-26T09:23:00+02:00", "2026-11-26T09:40:00+02:00", 1020, 170, 100, 97),
            o("2026-11-25T22:08:00+02:00", "2026-11-25T22:11:00+02:00", 180, 2, None, 97),
            o("2026-11-20T21:10:00+02:00", "2026-11-21T00:15:00+02:00", 11100, 590, 100, 86),
        ]
        groups = js(f"statsGroups({json.dumps(items)})")
        self.assertEqual([g["title"] for g in groups], ["Сьогодні", "Вчора", "Пт, 20.11"])
        self.assertEqual(groups[0]["lines"][0], {"cells": ["з 17:06", "59 хв", "0,56", "99→87"], "highlight": True})
        self.assertEqual(groups[0]["lines"][1]["cells"], ["09:23–09:40", "17 хв", "0,17", "100→97"])
        self.assertEqual(groups[1]["lines"][0]["cells"], ["22:08–22:11", "3 хв", "—", "—"])
        self.assertEqual(groups[2]["lines"][0]["cells"], ["21:10–00:15", "3 год 5 хв", "0,59", "100→86"])

    def test_charges(self):
        items = [
            {"start": "2026-11-26T09:40:00+02:00", "end": "2026-11-26T09:53:00+02:00", "seconds": 780, "energy_wh": 230,
             "soc_start": 97, "soc_end": 100, "estimated": False, "ongoing": False},
            {"start": "2026-11-24T10:42:00+02:00", "end": "2026-11-24T11:21:00+02:00", "seconds": 2340, "energy_wh": 750,
             "soc_start": None, "soc_end": None, "estimated": True, "ongoing": False},
        ]
        groups = js(f"statsGroups({json.dumps(items)}, true)")
        self.assertEqual([g["title"] for g in groups], ["Сьогодні", "Вт, 24.11"])
        self.assertEqual(groups[0]["lines"][0]["cells"], ["09:40–09:53", "13 хв", "+0,23", "97→100"])
        self.assertEqual(groups[1]["lines"][0]["cells"], ["10:42–11:21", "39 хв", "≈ +0,75", "—"])


if __name__ == "__main__":
    unittest.main()

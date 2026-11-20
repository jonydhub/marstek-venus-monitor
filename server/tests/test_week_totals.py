"""weekDayTotals() in app.js — the line next to each day of «Світло за 7 днів»; same cases as Android LightWeekTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"
H = 3600


def source():
    parts = []
    for name in ("spanText", "weekDayTotals"):
        match = re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S)
        assert match, f"{name} not found in app.js"
        parts.append(match.group(0))
    return "".join(parts)


def totals(off, on, unknown, today=False):
    day = {"off_seconds": off, "on_seconds": on, "unknown_seconds": unknown, "is_today": today}
    code = source() + f"process.stdout.write(JSON.stringify(weekDayTotals({json.dumps(day)})));"
    return json.loads(subprocess.run([NODE, "-e", code], capture_output=True, text=True, timeout=20, check=True).stdout)


@unittest.skipUnless(shutil.which("node"), "node not installed")
class WeekDayTotalsTests(unittest.TestCase):
    def test_day_with_outage(self):
        self.assertEqual(totals(4 * H + 58 * 60, 16 * H + 46 * 60, 0, today=True), "Без світла 4 год 58 хв · зі світлом 16 год 46 хв")

    def test_day_without_outage(self):
        self.assertEqual(totals(0, 24 * H, 0), "Без відключень")

    def test_short_gap_is_not_shown(self):
        self.assertEqual(totals(2 * H, 21 * H + 50 * 60, 10 * 60), "Без світла 2 год · зі світлом 21 год 50 хв")

    def test_long_gap_is_shown(self):
        self.assertEqual(totals(H, 20 * H, 3 * H), "Без світла 1 год · зі світлом 20 год · без даних 3 год")

    def test_no_data(self):
        self.assertEqual(totals(0, 0, 24 * H), "Немає даних")
        self.assertEqual(totals(0, 0, 5 * H, today=True), "Ще немає даних")


if __name__ == "__main__":
    unittest.main()

"""scheduleTotals() in app.js — «Без світла … · зі світлом …» under a schedule row; same cases as Android ScheduleTotalsTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node")
H = 3600 * 1000


def source():
    parts = []
    for name in ("spanText", "unionMs", "scheduleTotals"):
        match = re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S)
        assert match, f"{name} not found in app.js"
        parts.append(match.group(0))
    return "".join(parts)


def totals(windows, day_hours=24):
    clipped = [{"a": a * H, "b": b * H, "maybe": maybe} for a, b, maybe in windows]
    code = source() + f"process.stdout.write(JSON.stringify(scheduleTotals({json.dumps(clipped)}, {day_hours * H})));"
    result = subprocess.run([NODE, "-e", code], capture_output=True, text=True, timeout=20, check=True)
    return json.loads(result.stdout)


@unittest.skipUnless(NODE, "node not installed")
class ScheduleTotalsTests(unittest.TestCase):
    def test_screenshot_day(self):  # 10:30–14:00, 21:00–24:00
        self.assertEqual(totals([(10.5, 14, False), (21, 24, False)]), "Без світла 6 год 30 хв · зі світлом 17 год 30 хв")

    def test_three_windows(self):  # 04:00–07:30, 14:30–18:00, 21:30–24:00
        self.assertEqual(totals([(4, 7.5, False), (14.5, 18, False), (21.5, 24, False)]), "Без світла 9 год 30 хв · зі світлом 14 год 30 хв")

    def test_overlapping_windows_are_merged(self):
        self.assertEqual(totals([(8, 12, False), (10, 14, False)]), "Без світла 6 год · зі світлом 18 год")

    def test_maybe_counted_outside_definite_only(self):
        self.assertEqual(totals([(8, 12, False), (11, 14, True)]), "Без світла 4 год · зі світлом 18 год · ще можливе 2 год")

    def test_only_maybe(self):
        self.assertEqual(totals([(18, 20, True)]), "Без світла 0 хв · зі світлом 22 год · ще можливе 2 год")

    def test_no_windows_is_null(self):
        self.assertIsNone(totals([]))

    def test_dst_day_of_23_hours(self):
        self.assertEqual(totals([(0, 4, False)], day_hours=23), "Без світла 4 год · зі світлом 19 год")


if __name__ == "__main__":
    unittest.main()

"""reportRows() in app.js — «Звіт» table; same cases as Android ReportTableTest."""
import json
import re
import shutil
import subprocess
import unittest
from datetime import date
from pathlib import Path

from report import report
from tests.test_report import DAYS

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"


def rows(body):
    src = re.search(r"function kwhNumber\(.*?}\n", SCRIPT).group(0)  # type: ignore[union-attr]
    for name in ("spanText", "reportRows"):
        src += re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S).group(0)  # type: ignore[union-attr]
    out = subprocess.run([NODE, "-e", src + f"process.stdout.write(JSON.stringify(reportRows({json.dumps(body)})));"],
                         capture_output=True, text=True, encoding="utf-8", timeout=20, check=True).stdout
    return json.loads(out.replace("\\u00a0", " ").replace("\u00a0", " "))


@unittest.skipUnless(shutil.which("node"), "node not installed")
class ReportWebTests(unittest.TestCase):
    def test_week_with_previous(self):
        t = rows(report(DAYS, today=date(2026, 11, 26), period="week"))
        self.assertEqual(t["head"], ["", "Цей тиждень", "Минулий"])
        self.assertEqual(t["rows"], [
            ["Без світла", "6 год 9 хв", "10 год 29 хв"],
            ["Відключень", "5", "4"],
            ["Станція віддала, кВт·год", "2,05", "1,75"],
            ["Заряджено, кВт·год", "4,16", "4,78"],
            ["Вартість заряду, грн", "17,96", "20,63"],
        ])

    def test_month_without_previous(self):
        t = rows(report(DAYS, today=date(2026, 11, 26), period="month"))
        self.assertEqual(t["head"], ["", "Цей місяць"])
        self.assertEqual(t["rows"][0], ["Без світла", "16 год 38 хв"])

    def test_empty(self):
        self.assertIsNone(rows(report([], today=date(2026, 11, 26))))


if __name__ == "__main__":
    unittest.main()

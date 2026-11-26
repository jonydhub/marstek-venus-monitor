"""powerBadge() in app.js — the big power under the SOC; same cases as Android PowerBadgeTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"


def badge(flow):
    src = re.search(r"function powerBadge\(.*?\n}\n", SCRIPT, re.S).group(0)
    code = src + f"process.stdout.write(JSON.stringify(powerBadge({json.dumps(flow)})));"
    out = json.loads(subprocess.run([NODE, "-e", code], capture_output=True, text=True, encoding="utf-8", timeout=20,
                                    check=True).stdout)
    return out and dict(out, text=out["text"].replace("\u00a0", " "))


@unittest.skipUnless(shutil.which("node"), "node not installed")
class PowerBadgeTests(unittest.TestCase):
    def test_discharging(self):
        self.assertEqual(badge({"available": True, "direction": "discharging", "power_w": -445, "estimated": False}),
                         {"text": "↓ 445 Вт", "charging": False})

    def test_charging_kw(self):
        self.assertEqual(badge({"available": True, "direction": "charging", "power_w": 1100, "estimated": False}),
                         {"text": "↑ 1,1 кВт", "charging": True})

    def test_estimated(self):
        self.assertEqual(badge({"available": True, "direction": "discharging", "power_w": -445, "estimated": True})["text"],
                         "↓ ≈ 445 Вт")

    def test_hidden(self):
        self.assertIsNone(badge({"available": True, "direction": "idle", "power_w": 0}))
        self.assertIsNone(badge({"available": True, "direction": "discharging", "power_w": -5}))
        self.assertIsNone(badge({"available": False, "direction": "charging", "power_w": 900}))


if __name__ == "__main__":
    unittest.main()

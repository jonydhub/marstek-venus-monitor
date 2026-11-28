"""«Інші графіки» in app.js — extraSchedulesParse() and extraRow(); same cases as Android ExtraSchedulesTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node")
NAMES = ("kyivMidnight", "kyivHm", "spanText", "unionMs", "scheduleTotals", "scheduleDays", "extraSchedulesParse", "extraRow")
# 2026-11-28 12:00 Kyiv (UTC+2)
NOW = "2026-11-28T12:00:00+02:00"


def source():
    head = re.search(r"const EXTRA_KEY = .*?;\n", SCRIPT)
    assert head, "EXTRA_KEY not found in app.js"
    parts = [head.group(0)]
    for name in NAMES:
        match = re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S)
        assert match, f"{name} not found in app.js"
        parts.append(match.group(0))
    return "".join(parts)


def run(expr):
    assert NODE
    code = source() + f"process.stdout.write(JSON.stringify({expr}));"
    result = subprocess.run([NODE, "-e", code], capture_output=True, text=True, encoding="utf-8", timeout=20, check=True)
    return json.loads(result.stdout)


def parse(value):
    return run(f"extraSchedulesParse({json.dumps(value)})")


def row(outage, entry=None):
    entry = entry or {"name": "Батьки", "region": "lviv", "queue": "2.1"}
    r = run(f"extraRow({json.dumps(entry)}, {json.dumps(outage)}, Date.parse({json.dumps(NOW)}))")
    days = r.pop("days")
    return r, days


def outage(windows, published=True, **kw):
    base = {"available": True, "region": "lviv", "region_name": "Львівська область", "group": "2.1",
            "schedule_published": published, "schedule_dates": ["2026-11-28"] if published else [],
            "windows": [{"start": s, "end": e, "kind": k} for s, e, k in windows], "error": None,
            "schedule_unavailable": False, "emergency": False}
    base.update(kw)
    return base


@unittest.skipUnless(NODE, "node not installed")
class ExtraScheduleListTests(unittest.TestCase):
    def test_parse_cleans_names_drops_duplicates_and_bad_entries(self):
        raw = json.dumps([
            {"name": "  Робота  ", "region": "lviv", "queue": "1.1"},
            {"name": "", "region": "kyiv", "queue": "3.1"},
            {"name": "Дубль", "region": "lviv", "queue": "1.1"},
            {"name": "Без черги", "region": "lviv"},
            None,
            {"name": "Дуже довга назва графіка, що не влізе", "region": "lviv", "queue": "2.2"},
        ])
        self.assertEqual(parse(raw), [
            {"name": "Робота", "region": "lviv", "queue": "1.1"},
            {"name": "Графік", "region": "kyiv", "queue": "3.1"},
            {"name": "Дуже довга назва графіка", "region": "lviv", "queue": "2.2"},
        ])

    def test_parse_garbage_and_limit(self):
        self.assertEqual(parse("not json"), [])
        self.assertEqual(parse('{"a":1}'), [])
        self.assertEqual(parse(None), [])
        many = json.dumps([{"name": str(i), "region": "lviv", "queue": f"{i}.1"} for i in range(1, 10)])
        self.assertEqual(len(parse(many)), 6)


@unittest.skipUnless(NODE, "node not installed")
class ExtraRowTests(unittest.TestCase):
    def test_inside_definite_window_is_without_light(self):
        r, days = row(outage([("2026-11-28T11:00:00+02:00", "2026-11-28T14:30:00+02:00", "off")]))
        self.assertEqual(r, {"name": "Батьки", "sub": "Львівська обл. · черга 2.1", "state": "off", "status": "без світла"})
        self.assertEqual([(s["from"], s["to"]) for s in days[0]["segments"]], [("11:00", "14:30")])
        self.assertFalse(days[1]["published"])

    def test_outside_windows_light_on_maybe_does_not_count(self):
        r, _ = row(outage([("2026-11-28T11:00:00+02:00", "2026-11-28T13:00:00+02:00", "maybe"),
                           ("2026-11-28T18:00:00+02:00", "2026-11-28T21:00:00+02:00", "off")]))
        self.assertEqual((r["state"], r["status"]), ("on", "світло є"))

    def test_not_published(self):
        r, days = row(outage([], published=False))
        self.assertEqual((r["state"], r["status"]), ("none", "не опубліковано"))
        self.assertFalse(days[0]["published"])

    def test_unavailable_source_and_failed_fetch(self):
        r, days = row(outage([], published=False, available=False, schedule_unavailable=True))
        self.assertEqual((r["state"], r["status"]), ("na", "графік недоступний"))
        self.assertIsNone(days)
        r, _ = row(outage([], published=False, error="HTTPError"))
        self.assertEqual(r["status"], "графік недоступний")

    def test_loading_and_emergency(self):
        r, days = row(None, {"name": "Робота", "region": "kyiv", "queue": "3.1"})
        self.assertEqual(r, {"name": "Робота", "sub": "kyiv · черга 3.1", "state": "loading", "status": "…"})
        self.assertIsNone(days)
        r, _ = row(outage([], emergency=True))
        self.assertEqual((r["state"], r["status"]), ("off", "аварійні відключення"))


if __name__ == "__main__":
    unittest.main()

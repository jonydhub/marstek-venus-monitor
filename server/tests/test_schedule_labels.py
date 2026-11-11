"""scheduleTimeLabels() in app.js (layout of the time labels above the schedule bars), run under Node if present.

Same cases as the Android ScheduleTimeLabelsTest: 6 px per character, 4 px gap.
"""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node")


def source():
    match = re.search(r"function scheduleTimeLabels\(.*?\n// end scheduleTimeLabels", SCRIPT, re.S)
    assert match, "scheduleTimeLabels not found in app.js"
    return match.group(0)


def seg(a, b, maybe=False):
    return {"a": a / 24, "b": b / 24, "maybe": maybe, "from": f"{a:02d}:00", "to": "24:00" if b == 24 else f"{b:02d}:00"}


def layout(segments, now=None, now_text="10:42", width=240):
    code = source() + f"""
const out = scheduleTimeLabels({json.dumps(segments)}, {json.dumps(now)}, {json.dumps(now_text)}, {width}, t => t.length * 6, 4);
process.stdout.write(JSON.stringify(out));"""
    result = subprocess.run([NODE, "-e", code], capture_output=True, text=True, timeout=20, check=True)
    return json.loads(result.stdout)


def texts(labels):
    return [label["text"] for label in labels]


class FunctionShapeTests(unittest.TestCase):
    def test_function_and_renderer_are_wired(self):
        self.assertIn("function scheduleTimeLabels(", SCRIPT)
        self.assertIn("renderScheduleTimes(times, day", SCRIPT)
        self.assertIn("nowText: isToday ? kyivHm(nowMs) : null", SCRIPT)


@unittest.skipUnless(NODE, "node not installed")
class ScheduleTimeLabelTests(unittest.TestCase):
    def test_no_windows_shows_now_centred(self):
        labels = layout([], now=0.5, now_text="12:00")
        self.assertEqual(texts(labels), ["12:00"])
        self.assertAlmostEqual(labels[0]["left"] + labels[0]["w"] / 2, 120.0)
        self.assertEqual(labels[0]["kind"], "now")

    def test_window_edges(self):
        labels = layout([seg(7, 13)], now=0.75, now_text="18:00", width=480)
        self.assertEqual(texts(labels), ["07:00", "13:00", "18:00"])
        self.assertAlmostEqual(labels[0]["left"], 140.0)
        self.assertAlmostEqual(labels[1]["left"] + labels[1]["w"], 260.0)

    def test_short_window_merges(self):
        self.assertEqual(texts(layout([seg(7, 9)])), ["07:00–09:00"])

    def test_now_inside_labelled_window_hidden(self):
        labels = layout([seg(7, 13), seg(15, 21)], now=10.7 / 24, width=480)
        self.assertEqual(texts(labels), ["07:00", "13:00", "15:00", "21:00"])

    def test_now_colliding_hidden(self):
        self.assertNotIn("now", [l["kind"] for l in layout([seg(11, 16)], now=10.5 / 24)])

    def test_edges_clamped(self):
        labels = layout([seg(0, 24)], now=0.01)
        self.assertEqual(texts(labels), ["00:00", "24:00"])
        self.assertEqual(labels[0]["left"], 0)
        self.assertAlmostEqual(labels[-1]["left"] + labels[-1]["w"], 240.0)

    def test_crowded_merge_or_drop_never_overlap(self):
        self.assertEqual(texts(layout([seg(7, 13), seg(15, 21)])), ["07:00–13:00", "15:00–21:00"])
        self.assertEqual(texts(layout([seg(7, 13), seg(14, 15)])), ["07:00–13:00"])
        labels = layout([seg(7, 13), seg(14, 21), seg(22, 24, True)], now=0.3)
        for a, b in zip(labels, labels[1:]):
            self.assertLessEqual(a["left"] + a["w"] + 4, b["left"] + 0.01)

    def test_maybe_kind(self):
        self.assertEqual([l["kind"] for l in layout([seg(16, 20, True)], width=480)], ["maybe", "maybe"])


if __name__ == "__main__":
    unittest.main()

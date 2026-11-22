"""weekDayOutages / weekEnergyLabels / weekSkippedLine in app.js — times above and battery used under each outage
of «Світло за 7 днів»; same cases as Android WeekOutageLabelsTest."""
import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = (Path(__file__).resolve().parents[1] / "app.js").read_text(encoding="utf-8")
NODE = shutil.which("node") or "node"
NAMES = ("formatKwh", "kyivMidnight", "kyivHm", "scheduleTimeLabels", "weekEnergyTexts", "weekDayOutages", "weekEnergyLabels",
         "weekMissingLine")


def source():
    parts = []
    for name in NAMES:
        match = re.search(rf"function {name}\(.*?\n}}\n", SCRIPT, re.S)
        assert match, f"{name} not found in app.js"
        parts.append(match.group(0))
    return "".join(parts)


def run(expr):
    code = source() + f"process.stdout.write(JSON.stringify({expr}));"
    return json.loads(subprocess.run([NODE, "-e", code], capture_output=True, text=True, encoding="utf-8",
                                     timeout=20, check=True).stdout)


# Kyiv is UTC+2 in November: 20.11 00:00 Kyiv = 19.11 22:00Z
DAY20 = {"date": "2026-11-20", "is_today": False, "start": "2026-11-20T00:00:00+02:00", "end": "2026-11-21T00:00:00+02:00",
         "timeline": [
             {"start": "2026-11-20T00:00:00+02:00", "end": "2026-11-20T00:40:00+02:00", "state": "off"},
             {"start": "2026-11-20T00:40:00+02:00", "end": "2026-11-20T08:10:00+02:00", "state": "on"},
             {"start": "2026-11-20T08:10:00+02:00", "end": "2026-11-20T09:20:00+02:00", "state": "off"},
             {"start": "2026-11-20T09:20:00+02:00", "end": "2026-11-20T09:50:00+02:00", "state": "on"},
             {"start": "2026-11-20T09:50:00+02:00", "end": "2026-11-20T10:05:00+02:00", "state": "off"},
             {"start": "2026-11-20T10:05:00+02:00", "end": "2026-11-20T21:30:00+02:00", "state": "on"},
             {"start": "2026-11-20T21:30:00+02:00", "end": "2026-11-21T00:00:00+02:00", "state": "off"},
         ]}
STATS = [
    # yesterday 19.11 23:50 → 20.11 00:40: 10 min yesterday, 40 min on 20.11 → labelled on 20.11
    {"start": "2026-11-19T23:50:00+02:00", "end": "2026-11-20T00:40:00+02:00", "energy_wh": 90, "soc_start": 100, "soc_end": 97},
    {"start": "2026-11-20T08:10:00+02:00", "end": "2026-11-20T09:20:00+02:00", "energy_wh": 420, "soc_start": 80, "soc_end": 62},
    {"start": "2026-11-20T09:50:00+02:00", "end": "2026-11-20T10:05:00+02:00", "energy_wh": 60, "soc_start": 62, "soc_end": 60},
    # 21:30 → 21.11 03:00: 2 h 30 min on 20.11, 3 h on 21.11 → labelled on 21.11 only
    {"start": "2026-11-20T21:30:00+02:00", "end": "2026-11-21T03:00:00+02:00", "energy_wh": 900, "soc_start": 60, "soc_end": 35},
]
NOW = "Date.parse('2026-11-22T12:00:00+02:00')"


@unittest.skipUnless(shutil.which("node"), "node not installed")
class WeekOutageLabelsTests(unittest.TestCase):
    def outages(self):
        return run(f"weekDayOutages({json.dumps(DAY20)}, {json.dumps(STATS)}, {NOW})")

    def test_times_above_the_bar(self):
        o = self.outages()
        self.assertEqual([(x["from"], x["to"], x["label"]) for x in o],
                         [("00:00", "00:40", "до 00:40"), ("08:10", "09:20", ""), ("09:50", "10:05", ""), ("21:30", "00:00", "")])

    def test_energy_matched_and_midnight_crossing_labelled_once(self):
        o = self.outages()
        self.assertEqual([(x["energy"], x["socStart"], x["socEnd"]) for x in o],
                         [(90, 100, 97), (420, 80, 62), (60, 62, 60), (0, None, None)])

    def test_energy_texts(self):
        self.assertEqual(run("weekEnergyTexts({energy: 420, socStart: 80, socEnd: 62})"), ["0,42 кВт·год · 80→62 %", "0,42 кВт·год"])
        self.assertEqual(run("weekEnergyTexts({energy: 0, socStart: 80, socEnd: 62})"), ["80→62 %"])
        self.assertEqual(run("weekEnergyTexts({energy: 0, socStart: null, socEnd: null})"), [])

    def test_crowded_labels_fall_back_then_skip(self):
        # 300 px bar, 7 px per character: the biggest draw (08:10, 0,42) is placed first, centred; the 00:00 and 09:50
        # ones next to it fit neither long nor short → listed in the line, in time order
        expr = (f"(() => {{ const o = weekDayOutages({json.dumps(DAY20)}, {json.dumps(STATS)}, {NOW});"
                " const l = weekEnergyLabels(o, 300, t => t.length * 7);"
                " return {texts: l.map(x => x.text), line: weekMissingLine(o, [], l.skipped)}; })()")
        r = run(expr)
        self.assertEqual(r["texts"], ["0,42 кВт·год · 80→62 %"])
        self.assertEqual(r["line"], "до 00:40 — 0,09 кВт·год · 100→97 %; 09:50–10:05 — 0,06 кВт·год · 62→60 %")

    def test_wide_bar_fits_all(self):
        expr = (f"(() => {{ const o = weekDayOutages({json.dumps(DAY20)}, {json.dumps(STATS)}, {NOW});"
                " const l = weekEnergyLabels(o, 2400, t => t.length * 6);"
                " return {texts: l.map(x => x.text), line: weekMissingLine(o, [], l.skipped)}; })()")
        r = run(expr)
        self.assertEqual(r["texts"], ["0,09 кВт·год · 100→97 %", "0,42 кВт·год · 80→62 %", "0,06 кВт·год · 62→60 %"])

    def test_short_text_when_long_does_not_fit(self):
        # 400 px, 6 px per character: the two big outages at the edges take 0–132 and 268–400; the middle one has a
        # 128 px gap — «0,10 кВт·год · 70→68 %» (132 px) does not fit, «0,10 кВт·год» (72 px) does
        outages = [
            {"a": 0.00, "b": 0.05, "energy": 500, "socStart": 90, "socEnd": 70, "from": "00:00", "to": "01:12", "label": "до 01:12"},
            {"a": 0.50, "b": 0.52, "energy": 100, "socStart": 70, "socEnd": 68, "from": "12:00", "to": "12:29", "label": ""},
            {"a": 0.95, "b": 1.00, "energy": 400, "socStart": 68, "socEnd": 52, "from": "22:48", "to": "00:00", "label": ""},
        ]
        r = run(f"(() => {{ const o = {json.dumps(outages)}; const l = weekEnergyLabels(o, 400, t => t.length * 6);"
                " return {texts: l.map(x => x.text), line: weekMissingLine(o, [], l.skipped)}; })()")
        self.assertEqual(r["texts"], ["0,50 кВт·год · 90→70 %", "0,10 кВт·год", "0,40 кВт·год · 68→52 %"])
        self.assertEqual(r["line"], "")

    def test_ivans_20_11_long_outage_gets_its_time_and_the_rest_goes_in_the_line(self):
        # live data of 20.11 on a 350 px phone bar (6 px per character): 07:47–08:49 (62 min) next to 10:34–13:52 (3 h 18 min)
        day = {"date": "2026-11-20", "is_today": False, "start": "2026-11-20T00:00:00+02:00", "end": "2026-11-21T00:00:00+02:00",
               "timeline": [{"start": "2026-11-20T06:47:00+02:00", "end": "2026-11-20T06:47:20+02:00", "state": "off"},
                            {"start": "2026-11-20T07:47:00+02:00", "end": "2026-11-20T08:49:00+02:00", "state": "off"},
                            {"start": "2026-11-20T10:34:00+02:00", "end": "2026-11-20T13:52:00+02:00", "state": "off"},
                            {"start": "2026-11-20T21:06:00+02:00", "end": "2026-11-21T00:00:00+02:00", "state": "off"}]}
        stats = [{"start": "2026-11-20T21:06:00+02:00", "end": "2026-11-21T00:15:00+02:00", "energy_wh": 595.9, "soc_start": 100, "soc_end": 86},
                 {"start": "2026-11-20T10:34:00+02:00", "end": "2026-11-20T13:52:00+02:00", "energy_wh": 499.8, "soc_start": 100, "soc_end": 88},
                 {"start": "2026-11-20T07:47:00+02:00", "end": "2026-11-20T08:49:00+02:00", "energy_wh": 160.1, "soc_start": 100, "soc_end": 96}]
        expr = (f"(() => {{ const o = weekDayOutages({json.dumps(day)}, {json.dumps(stats)}, {NOW}); const m = t => t.length * 6;"
                " const t = scheduleTimeLabels(o, null, null, 350, m); const e = weekEnergyLabels(o, 350, m);"
                " return {times: t.map(x => x.text), energy: e.map(x => x.text), line: weekMissingLine(o, t.unlabeled, e.skipped)}; })()")
        r = run(expr)
        self.assertIn("10:34–13:52", r["times"])
        self.assertEqual(r["energy"], ["0,50 кВт·год · 100→88 %", "0,60 кВт·γод · 100→86 %".replace("γ", "г")])
        self.assertEqual(r["line"], "07:47–08:49 — 0,16 кВт·год · 100→96 %")

    def test_running_outage_today(self):
        today = {"date": "2026-11-22", "is_today": True, "start": "2026-11-22T00:00:00+02:00", "end": "2026-11-23T00:00:00+02:00",
                 "timeline": [{"start": "2026-11-22T00:00:00+02:00", "end": "2026-11-22T11:00:00+02:00", "state": "on"},
                              {"start": "2026-11-22T11:00:00+02:00", "end": "2026-11-22T11:58:00+02:00", "state": "off"}]}
        o = run(f"weekDayOutages({json.dumps(today)}, [], {NOW})")
        self.assertEqual([(x["label"], x["energy"]) for x in o], [("з 11:00", 0)])

    def test_short_blip_is_not_an_outage(self):
        day = dict(DAY20, timeline=[{"start": "2026-11-20T05:00:00+02:00", "end": "2026-11-20T05:00:40+02:00", "state": "off"}])
        self.assertEqual(run(f"weekDayOutages({json.dumps(day)}, [], {NOW})"), [])


if __name__ == "__main__":
    unittest.main()

"""1.3.7 dashboard helpers run in node: chart-marker clustering, the «Фактично» row, «За графіком» text, today's kWh."""
import json
import shutil
import subprocess
import unittest
from pathlib import Path

from tests.test_dashboard_style import _extract

ROOT = Path(__file__).resolve().parents[1]
HELPERS = ("formatClock", "kyivMidnight", "kyivHm", "clusterChartEvents", "actualDay", "scheduleNowText", "todayKwh")


@unittest.skipUnless(shutil.which("node"), "node not installed")
class WebV137Tests(unittest.TestCase):
    def run_js(self, body):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        start = script.index("const EVENT_MARK")
        consts = script[start:script.index("function clusterChartEvents")]
        prelude = ("const finiteOrNull = v => v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v);\n"
                   + consts)
        code = prelude + "\n".join(_extract(script, n) for n in HELPERS) + "\n" + body
        return json.loads(subprocess.run(["node", "-e", code], capture_output=True, text=True, timeout=20, check=True).stdout)

    def test_events_within_twenty_minutes_merge_into_one_counted_marker(self):
        out = self.run_js("""
            const t0 = Date.parse('2026-11-11T21:46:45+02:00'), iso = m => new Date(t0 + m * 60e3).toISOString();
            const ev = [['light_lost', 0], ['light_returned', 4.5], ['light_lost', 5.2], ['anomaly_overload_trip', 21.8],
                        ['light_returned', 28.3], ['charged_full', 300]].map(([kind, m]) => ({kind, t: iso(m)}));
            const x = ms => (ms - t0) / (24 * 3600e3) * 282;  // the 282-px wide web chart
            console.log(JSON.stringify(clusterChartEvents(ev, x).map(c => [c.count, c.kind])));
        """)
        # 21:46–22:15 is one evening of events → one marker «5» (trip has priority); 300 min later stays alone
        self.assertEqual([[5, "anomaly_overload_trip"], [1, "charged_full"]], out)

    def test_far_apart_events_on_a_wide_chart_stay_separate(self):
        out = self.run_js("""
            const t0 = Date.parse('2026-11-11T08:00:00+02:00'), iso = m => new Date(t0 + m * 60e3).toISOString();
            const ev = [{kind: 'light_lost', t: iso(0)}, {kind: 'light_returned', t: iso(30)}];
            console.log(JSON.stringify([clusterChartEvents(ev, ms => (ms - t0) / 864e5 * 2000).length,
                                        clusterChartEvents(ev, ms => (ms - t0) / 864e5 * 282).length]));
        """)
        self.assertEqual([2, 1], out)  # 30 min apart: 41 px on a 2000-px chart, 6 px (overlap) on 282 px

    def test_actual_row_segments_and_header(self):
        out = self.run_js("""
            const day = kyivMidnight(new Date('2026-11-11T22:16:00+02:00')), h = x => new Date(day + x * 3600e3).toISOString();
            const today = {grid_timeline: [{start: h(0), end: h(21.6), state: 'unknown'}, {start: h(21.6), end: h(21.78), state: 'off'},
                                           {start: h(21.78), end: h(21.86), state: 'on'}, {start: h(21.86), end: h(22.25), state: 'off'}]};
            const a = actualDay(today, day + 22.25 * 3600e3);
            console.log(JSON.stringify([a.text, a.state, a.segments.length, Math.round(a.now * 1000), Math.round(a.segments[3].fa * 1000)]));
        """)
        self.assertEqual(["Фактично: Світла нема (з 21:51)", "off", 4, 927, 911], out)

    def test_actual_row_without_fresh_data_says_so(self):
        out = self.run_js("""
            const day = kyivMidnight(new Date('2026-11-11T22:16:00+02:00')), h = x => new Date(day + x * 3600e3).toISOString();
            const a = actualDay({grid_timeline: [{start: h(8), end: h(9), state: 'on'}]}, day + 22 * 3600e3);
            console.log(JSON.stringify([a.text, actualDay({}, Date.now())]));
        """)
        self.assertEqual(["Фактично: немає даних", None], out)

    def test_schedule_now_text(self):
        out = self.run_js("""
            const now = Date.parse('2026-11-11T10:42:00+02:00'), iso = m => new Date(now + m * 60e3).toISOString();
            console.log(JSON.stringify([
              scheduleNowText({available: true, schedule_published: true, current_outage: {start: iso(-60), end: iso(138)}}, now),
              scheduleNowText({available: true, schedule_published: true, next_outage: {start: iso(258)}}, now),
              scheduleNowText({available: true, schedule_published: false}, now),
              scheduleNowText({available: false}, now)]));
        """)
        self.assertEqual(["За графіком: відключення до 13:00", "За графіком: світло є, відключення о 15:00",
                          "За графіком: графік не опубліковано", None], out)

    def test_today_kwh_uses_ukrainian_units(self):
        out = self.run_js("console.log(JSON.stringify([todayKwh(810, false), todayKwh(150, true), todayKwh(null, false)]));")
        self.assertEqual(["0,81\u00a0кВт·год", "≈\u00a00,15\u00a0кВт·год", ""], out)

    def test_ui_strings_use_ukrainian_units(self):
        for name in ("app.js", "index.html"):
            text = (ROOT / name).read_text(encoding="utf-8")
            for bad in ("kWh", "'W'", " W`", "· W<", "\"W\""):
                self.assertNotIn(bad, text, f"{name}: {bad}")


if __name__ == "__main__":
    unittest.main()

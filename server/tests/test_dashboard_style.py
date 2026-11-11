import subprocess
import shutil
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class OfficialStyleDashboardTests(unittest.TestCase):
    def test_dashboard_explains_energy_direction_without_house_illustration(self):
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        for marker in (
            'class="official-shell"',
            'class="battery-summary"',
            'class="energy-flow-panel"',
            'id="capacity-kwh"',
            'id="soc-value"',
            'id="battery-flow-power"',
            'id="battery-flow-arrow"',
            'data-flow="grid-signed"',
            'data-flow="battery-estimate"',
            'data-flow="venus-to-backup"',
            'id="total-grid-in"',
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, html)
        self.assertNotIn('class="scene-art"', html)
        self.assertNotIn('class="cycles-card"', html)
        self.assertNotIn('id="estimated-cycles"', html)
        self.assertNotIn('id="native-cycles"', html)
        self.assertIn('class="flow-copy-text"', html)
        self.assertEqual(html.count('class="flow-icon"'), 3)
        self.assertIn('class="mark-v"', html)

    def test_script_keeps_measured_flow_direction_and_drops_cycle_display(self):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        self.assertIn("function formatKwh", script)
        self.assertIn("function renderBatteryFlow", script)
        self.assertIn("battery-flow-arrow", script)
        self.assertIn("grid-sign-note", script)
        self.assertNotIn("battery_cycles", script)
        self.assertNotIn("estimated-cycles", script)
        self.assertNotIn("native-cycles", script)

    def test_styles_keep_the_flow_map_clear_and_mobile_responsive(self):
        css = (ROOT / "style.css").read_text(encoding="utf-8")
        self.assertIn(".official-shell", css)
        self.assertIn(".energy-flow-panel", css)
        self.assertIn(".flow-entry", css)
        self.assertIn(".flow-arrow", css)
        self.assertIn(".flow-copy b{display:block", css)
        self.assertIn("grid-template-columns:minmax(0,1fr)", css)
        self.assertIn("font-size:14px", css)
        self.assertIn("@media(max-width:520px)", css)
        self.assertIn("--solar", css)
        self.assertNotIn(".cycles-card", css)

    def test_context_hero_sits_above_battery_summary(self):
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        for marker in ('id="hero"', 'id="hero-title"', 'id="hero-sub"', 'id="hero-forecast"',
                       'id="today-charge"', 'id="today-discharge"', 'id="today-outage"', 'id="schedule-days"',
                       'id="soc-chart"', 'id="backup-today"', 'id="journal"', 'id="journal-csv"'):
            with self.subTest(marker=marker):
                self.assertIn(marker, html)
        self.assertLess(html.index('id="hero"'), html.index('class="battery-summary"'))
        self.assertNotIn('id="power-panel"', html)  # old light card merged into hero
        # SOC % and kWh live only in the battery card (no duplicate tiles in the hero)
        self.assertNotIn('id="hero-soc"', html)
        self.assertNotIn('id="hero-extra"', html)
        self.assertEqual(html.count('ЗАПАС ЕНЕРГІЇ'), 1)

    def test_script_renders_hero_from_insights_without_external_libs(self):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        self.assertIn("function renderHero", script)
        self.assertIn("function renderSocChart", script)
        self.assertIn("function renderScheduleDays", script)
        self.assertIn("function scheduleDetail", script)
        self.assertIn("outage_periods", script)
        self.assertIn("chart_events", script)
        self.assertIn("/api/events", script)
        self.assertIn("data.insights", script)
        self.assertIn("measured_average_24h", script)
        self.assertNotIn("<script src=\"http", (ROOT / "index.html").read_text(encoding="utf-8"))

def _extract(script, name):
    """Source of one top-level `function name(...) {...}` (brace matched)."""
    start = script.index(f"function {name}(")
    depth, i = 0, script.index(") {", start) + 2
    while True:
        if script[i] == "{":
            depth += 1
        elif script[i] == "}":
            depth -= 1
            if depth == 0:
                return script[start:i + 1]
        i += 1


@unittest.skipUnless(shutil.which("node"), "node not installed")
class WebScheduleTextTests(unittest.TestCase):
    """Hero text never says «Повернення о …» while the backup output shows the light is on."""

    def run_js(self, body):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        funcs = "\n".join(_extract(script, n) for n in ("formatClock", "kyivMidnight", "kyivHm", "scheduleDetail", "scheduleDays"))
        code = funcs + "\n" + body
        return subprocess.run(["node", "-e", code], capture_output=True, text=True, timeout=20, check=True).stdout.strip()

    def test_grid_on_during_window_and_return_only_when_off(self):
        out = self.run_js("""
            const now = Date.now(), iso = t => new Date(t).toISOString();
            const cur = {start: iso(now - 3600e3), end: iso(now + 3600e3)};
            const o = {available: true, schedule_published: true, current_outage: cur};
            console.log(JSON.stringify([scheduleDetail(o, 'on'), scheduleDetail(o, 'off'), scheduleDetail(o, 'unknown')]));
        """)
        on, off, unknown = json.loads(out)
        self.assertTrue(on.startswith("За графіком відключення до ") and on.endswith(" · світло є"), on)
        self.assertTrue(off.startswith("Світло за графіком о "), off)
        self.assertTrue(unknown.startswith("За графіком відключення до ") and "світло" not in unknown, unknown)

    def test_durations_use_full_unit_words(self):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        funcs = "const finiteOrNull = v => v === null || v === undefined || !Number.isFinite(Number(v)) ? null : Number(v);\n" + \
            "\n".join(_extract(script, n) for n in ("shortDuration", "formatDuration"))
        out = subprocess.run(["node", "-e", funcs + """
            console.log(JSON.stringify([shortDuration(3600), shortDuration(7500), shortDuration(60), shortDuration(0),
              formatDuration(3600), formatDuration(12120)]));"""], capture_output=True, text=True, timeout=20, check=True).stdout
        self.assertEqual(json.loads(out), ["1\u00a0год", "2\u00a0год 5\u00a0хв", "1\u00a0хв", "0\u00a0хв", "1 год", "3 год 22 хв"])

    def test_days_split_windows_and_mark_tomorrow_unpublished(self):
        out = self.run_js("""
            const start = kyivMidnight(new Date());
            const w = (a, b, kind) => ({start: new Date(start + a * 3600e3).toISOString(), end: new Date(start + b * 3600e3).toISOString(), kind});
            const o = {available: true, schedule_published: true, schedule_dates: [], windows: [w(7, 13, 'off'), w(15, 21, 'off'), w(21, 22, 'maybe')]};
            const days = scheduleDays(o, start + 9.5 * 3600e3);
            console.log(JSON.stringify(days.map(d => [d.title, d.published, d.text, d.maybe])));
        """)
        today, tomorrow = json.loads(out)
        self.assertEqual(today, ["Сьогодні", True, "Відключення 07:00–13:00, 15:00–21:00", ["21:00–22:00"]])
        self.assertEqual(tomorrow[:3], ["Завтра", False, "Графік ще не опубліковано"])


if __name__ == "__main__":
    unittest.main()


class NoSolarTests(unittest.TestCase):
    def test_dashboard_has_no_pv_or_solar(self):
        from pathlib import Path
        root = Path(__file__).resolve().parents[1]
        for name in ("index.html", "app.js"):
            text = (root / name).read_text(encoding="utf-8")
            self.assertNotIn("pv_power", text)
            self.assertNotIn("PV", text)
            self.assertNotIn("Сонячн", text)


class TokenAndSourceUiTests(unittest.TestCase):
    def test_dashboard_sends_bearer_token_and_prompts_on_401(self):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        self.assertIn("Authorization", script)
        self.assertIn("Bearer ", script)
        self.assertIn("localStorage", script)
        self.assertIn("status === 401", script)
        self.assertIn("token-form", script)
        self.assertIn("replaceState", script)  # ?token= bootstrap is stripped from the address bar

    def test_dashboard_shows_outage_source_and_emergency(self):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        self.assertIn("source_name", script)
        self.assertIn("outage-source", script)
        self.assertIn("emergency", script)
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        self.assertIn('id="token-form"', html)
        self.assertNotIn("NUC", html)
        self.assertNotIn(">Львівобленерго<", html)


class LightOnlyFromBackupOutputWebTests(unittest.TestCase):
    def test_lost_connection_is_shown_as_no_link_not_no_light(self):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        self.assertIn("function lightView", script)
        self.assertIn("Немає зв’язку з батареєю", script)
        self.assertIn("за графіком зараз відключення", script)
        body = script[script.index("function lightView"):script.index("function renderToday")]
        self.assertLess(body.index("noLink"), body.index("'Світла нема'"))  # no-link wins over a cached «off»

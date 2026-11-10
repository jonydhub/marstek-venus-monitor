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
                       'id="hero-soc"', 'id="hero-temp"', 'id="soc-sparkline"', 'id="backup-today"'):
            with self.subTest(marker=marker):
                self.assertIn(marker, html)
        self.assertLess(html.index('id="hero"'), html.index('class="battery-summary"'))
        self.assertNotIn('id="power-panel"', html)  # old light card merged into hero

    def test_script_renders_hero_from_insights_without_external_libs(self):
        script = (ROOT / "app.js").read_text(encoding="utf-8")
        self.assertIn("function renderHero", script)
        self.assertIn("function renderSparkline", script)
        self.assertIn("data.insights", script)
        self.assertIn("measured_average_24h", script)
        self.assertNotIn("<script src=\"http", (ROOT / "index.html").read_text(encoding="utf-8"))


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

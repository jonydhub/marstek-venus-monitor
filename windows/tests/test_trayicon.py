import unittest

from tests import _path  # noqa: F401
from mvm import trayicon
from tests.test_alerts import status


class StateSelectionTests(unittest.TestCase):
    def test_states(self):
        self.assertEqual("charging", trayicon.icon_state(status(direction="charging"), stale=False))
        self.assertEqual("discharging", trayicon.icon_state(status(direction="discharging"), stale=False))
        self.assertEqual("idle", trayicon.icon_state(status(direction="idle"), stale=False))
        self.assertEqual("no_light", trayicon.icon_state(status(grid="off", direction="discharging"), stale=False))
        self.assertEqual("stale", trayicon.icon_state(status(grid="off"), stale=True))
        self.assertEqual("unknown", trayicon.icon_state(None, stale=False))
        self.assertEqual("unknown", trayicon.icon_state({"methods": {}}, stale=False))

    def test_colours_are_distinct(self):
        colours = {trayicon.STATE_COLOURS[s] for s in ("charging", "discharging", "no_light", "idle", "stale")}
        self.assertEqual(5, len(colours))


class TextTests(unittest.TestCase):
    def test_icon_text(self):
        self.assertEqual("87", trayicon.icon_text(87))
        self.assertEqual("5", trayicon.icon_text(5))
        self.assertEqual("100", trayicon.icon_text(100))
        self.assertEqual("?", trayicon.icon_text(None))
        self.assertEqual("0", trayicon.icon_text(-3))

    def test_tooltip_contents_and_limit(self):
        tip = trayicon.tooltip(status(soc=64, direction="charging", bat_wh=3277.0))
        self.assertIn("64%", tip)
        self.assertIn("3.28 kWh", tip)
        self.assertIn("Світло є", tip)
        self.assertIn("до 100%", tip)
        self.assertLessEqual(len(tip), trayicon.TOOLTIP_MAX)

    def test_tooltip_without_light(self):
        tip = trayicon.tooltip(status(soc=40, grid="off", direction="discharging", offgrid=210.0))
        self.assertIn("Світла немає", tip)
        self.assertIn("до 0%", tip)

    def test_tooltip_without_data(self):
        self.assertIn("немає даних", trayicon.tooltip(None).lower())
        self.assertIn("налаштуйте", trayicon.tooltip(None, configured=False).lower())

    def test_tooltip_stale(self):
        self.assertIn("немає зв", trayicon.tooltip(status(), stale=True).lower())

    def test_lost_connection_never_reports_no_light(self):
        tip = trayicon.tooltip(status(grid="off", direction="discharging", offgrid=210.0), stale=True)
        self.assertNotIn("Світла немає", tip)
        self.assertIn("Світло: невідомо", tip)
        self.assertEqual("stale", trayicon.icon_state(status(grid="off"), stale=True))


class RenderTests(unittest.TestCase):
    def test_render_returns_rgba_square(self):
        try:
            import PIL  # noqa: F401
        except ImportError:
            self.skipTest("Pillow not installed")
        img = trayicon.render_icon("87", trayicon.STATE_COLOURS["charging"], size=64)
        self.assertEqual((64, 64), img.size)
        self.assertEqual("RGBA", img.mode)
        self.assertEqual(0, img.getpixel((0, 0))[3])  # transparent corner
        img3 = trayicon.render_icon("100", trayicon.STATE_COLOURS["idle"], size=32)
        self.assertEqual((32, 32), img3.size)


if __name__ == "__main__":
    unittest.main()

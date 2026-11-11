import re
import unittest

from tests import _path
from mvm import VERSION
from mvm.app import parse_args

PACK = _path.WINDOWS / "packaging"


class VersionTests(unittest.TestCase):
    def test_version_is_1_2_0_everywhere(self):
        self.assertEqual("1.3.2", VERSION)
        info = (PACK / "version_info.txt").read_text("utf-8")
        self.assertIn("filevers=(1, 3, 2, 0)", info)
        self.assertEqual({VERSION}, set(re.findall(r"Version', '([0-9.]+)'", info)))
        self.assertIn(f'#define AppVersion "{VERSION}"', (PACK / "installer.iss").read_text("utf-8"))


class JournalMenuTests(unittest.TestCase):
    def test_tray_menu_opens_the_server_journal(self):
        source = (_path.WINDOWS / "mvm" / "app.py").read_text("utf-8")
        self.assertIn('item("Журнал подій", self.open_journal)', source)
        self.assertIn('"#journal"', source)
        self.assertIn('id="journal"', (_path.SERVER / "index.html").read_text("utf-8"))


class PackagingInputsTests(unittest.TestCase):
    def test_bundled_files_exist(self):
        for rel in ("assets/app.ico", "mvm/web/setup.html", "mvm/web/setup.js", "mvm/web/setup.css"):
            self.assertTrue((_path.WINDOWS / rel).is_file(), rel)
        for name in ("index.html", "app.js", "style.css", "sw.js", "favicon.svg", "manifest.webmanifest"):
            self.assertTrue((_path.SERVER / name).is_file(), name)

    def test_installer_is_per_user_and_cleans_autostart(self):
        iss = (PACK / "installer.iss").read_text("utf-8")
        self.assertIn("PrivilegesRequired=lowest", iss)
        self.assertIn("uninsdeletevalue", iss)
        self.assertIn("AppMutex=Local\\MarstekVenusMonitor.SingleInstance", iss)


class CliTests(unittest.TestCase):
    def test_flags(self):
        args = parse_args(["--autostart"])
        self.assertTrue(args.autostart)
        self.assertIsNone(args.window)
        self.assertEqual("http://127.0.0.1:8767/", parse_args(["--window", "http://127.0.0.1:8767/"]).window)
        self.assertTrue(parse_args(["--version"]).version)

    def test_unknown_args_are_ignored(self):
        self.assertFalse(parse_args(["--whatever"]).autostart)


if __name__ == "__main__":
    unittest.main()

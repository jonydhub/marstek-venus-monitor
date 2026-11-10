import unittest

from tests import _path  # noqa: F401
from mvm import autostart


class FakeKey:
    def __init__(self, store):
        self.store = store

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeWinreg:
    HKEY_CURRENT_USER = "HKCU"
    KEY_READ, KEY_SET_VALUE, REG_SZ = 1, 2, 1

    def __init__(self):
        self.values = {}
        self.opened = []

    def OpenKey(self, root, path, reserved=0, access=0):
        self.opened.append((root, path, access))
        return FakeKey(self.values)

    def CreateKeyEx(self, root, path, reserved=0, access=0):
        self.opened.append((root, path, access))
        return FakeKey(self.values)

    def QueryValueEx(self, key, name):
        if name not in self.values:
            raise FileNotFoundError(name)
        return self.values[name], self.REG_SZ

    def SetValueEx(self, key, name, reserved, kind, value):
        self.values[name] = value

    def DeleteValue(self, key, name):
        if name not in self.values:
            raise FileNotFoundError(name)
        del self.values[name]


EXE = r"C:\Program Files\Marstek Venus Monitor\MarstekVenusMonitor.exe"


class AutostartTests(unittest.TestCase):
    def setUp(self):
        self.reg = FakeWinreg()
        self.auto = autostart.Autostart(EXE, winreg_module=self.reg)

    def test_command_is_quoted_with_flag(self):
        self.assertEqual(f'"{EXE}" --autostart', self.auto.command)

    def test_enable_disable_round_trip(self):
        self.assertFalse(self.auto.is_enabled())
        self.auto.set_enabled(True)
        self.assertTrue(self.auto.is_enabled())
        self.assertEqual(self.auto.command, self.reg.values[autostart.VALUE_NAME])
        self.assertTrue(all(r == "HKCU" and p == autostart.RUN_KEY for r, p, _ in self.reg.opened))
        self.auto.set_enabled(False)
        self.assertFalse(self.auto.is_enabled())
        self.auto.set_enabled(False)  # idempotent

    def test_entry_for_another_copy_is_not_reported_as_ours(self):
        self.reg.values[autostart.VALUE_NAME] = r'"D:\old\MarstekVenusMonitor.exe" --autostart'
        self.assertFalse(self.auto.is_enabled())
        self.auto.set_enabled(True)
        self.assertTrue(self.auto.is_enabled())

    def test_unavailable_without_winreg(self):
        auto = autostart.Autostart(EXE, winreg_module=None)
        self.assertFalse(auto.available)
        self.assertFalse(auto.is_enabled())
        with self.assertRaises(RuntimeError):
            auto.set_enabled(True)


if __name__ == "__main__":
    unittest.main()

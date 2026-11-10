"""«Запускати з Windows»: HKCU\\...\\Run value (per user, no admin rights)."""
from __future__ import annotations

import os

try:
    import winreg as _winreg
except ImportError:  # not Windows
    _winreg = None

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "MarstekVenusMonitor"


class Autostart:
    def __init__(self, exe_path: str, winreg_module=_winreg, args: str = "--autostart"):
        self.exe_path = str(exe_path)
        self.reg = winreg_module
        self.command = f'"{self.exe_path}" {args}'.strip()

    @property
    def available(self) -> bool:
        return self.reg is not None

    def _current(self):
        if not self.available:
            return None
        try:
            with self.reg.OpenKey(self.reg.HKEY_CURRENT_USER, RUN_KEY, 0, self.reg.KEY_READ) as key:
                value, _ = self.reg.QueryValueEx(key, VALUE_NAME)
                return value
        except OSError:
            return None

    def is_enabled(self) -> bool:
        value = self._current()
        if not value:
            return False
        exe = value.strip()
        exe = exe[1:].split('"', 1)[0] if exe.startswith('"') else exe.split(" ", 1)[0]
        return os.path.normcase(os.path.normpath(exe)) == os.path.normcase(os.path.normpath(self.exe_path))

    def set_enabled(self, enabled: bool) -> None:
        if not self.available:
            raise RuntimeError("autostart is only available on Windows")
        with self.reg.CreateKeyEx(self.reg.HKEY_CURRENT_USER, RUN_KEY, 0, self.reg.KEY_SET_VALUE) as key:
            if enabled:
                self.reg.SetValueEx(key, VALUE_NAME, 0, self.reg.REG_SZ, self.command)
            else:
                try:
                    self.reg.DeleteValue(key, VALUE_NAME)
                except FileNotFoundError:
                    pass

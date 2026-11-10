"""Tiny ctypes helpers (Windows only; harmless no-ops elsewhere)."""
from __future__ import annotations

import sys

ERROR_ALREADY_EXISTS = 183
MUTEX_NAME = "Local\\MarstekVenusMonitor.SingleInstance"


class SingleInstance:
    """Named mutex held for the process lifetime; `already_running` if another instance owns it."""

    def __init__(self, name=MUTEX_NAME):
        self.handle = None
        self.already_running = False
        if sys.platform != "win32":
            return
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CreateMutexW.restype = wintypes.HANDLE
        kernel32.CreateMutexW.argtypes = (wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR)
        self._kernel32 = kernel32
        self.handle = kernel32.CreateMutexW(None, False, name)
        self.already_running = ctypes.get_last_error() == ERROR_ALREADY_EXISTS

    def release(self):
        if self.handle:
            self._kernel32.CloseHandle(self.handle)
            self.handle = None


def message_box(text, title, error=False):
    if sys.platform != "win32":
        print(f"{title}: {text}")
        return
    import ctypes
    MB_ICONINFORMATION, MB_ICONERROR, MB_SETFOREGROUND, MB_TOPMOST = 0x40, 0x10, 0x10000, 0x40000
    flags = (MB_ICONERROR if error else MB_ICONINFORMATION) | MB_SETFOREGROUND | MB_TOPMOST
    ctypes.windll.user32.MessageBoxW(None, text, title, flags)


def set_app_user_model_id(app_id):
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(app_id)
        except Exception:
            pass

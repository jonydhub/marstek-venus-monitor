"""Per-user locations: config in %APPDATA%, data and logs in %LOCALAPPDATA%."""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from pathlib import Path

from mvm import APP_ID


@dataclass(frozen=True)
class AppPaths:
    config_file: Path
    data_dir: Path
    history_db: Path
    log_dir: Path
    webview_dir: Path


def app_paths(env=None) -> AppPaths:
    env = os.environ if env is None else env
    home = Path.home()
    roaming = Path(env.get("APPDATA") or home / "AppData" / "Roaming") / APP_ID
    local = Path(env.get("LOCALAPPDATA") or home / "AppData" / "Local") / APP_ID
    return AppPaths(config_file=roaming / "config.json", data_dir=local, history_db=local / "history.db",
                    log_dir=local / "logs", webview_dir=local / "webview")


def resource_dir() -> Path:
    """Folder with bundled resources (PyInstaller _MEIPASS) or the repo windows/ folder."""
    frozen = getattr(sys, "_MEIPASS", None)
    return Path(frozen) if frozen else Path(__file__).resolve().parents[1]


def server_dir() -> Path:
    frozen = getattr(sys, "_MEIPASS", None)
    return Path(frozen) / "server" if frozen else Path(__file__).resolve().parents[2] / "server"


def ensure_server_on_path() -> Path:
    path = server_dir()
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))
    return path

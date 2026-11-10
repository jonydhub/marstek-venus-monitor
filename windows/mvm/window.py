"""Dashboard/settings window: pywebview (WebView2) in its own process; default browser as fallback."""
from __future__ import annotations

import logging
import webbrowser

from mvm import APP_NAME

log = logging.getLogger(__name__)


def run_window(url: str, title: str = APP_NAME, storage_dir=None) -> int:
    try:
        import webview
    except Exception as exc:  # pywebview / pythonnet / WebView2 missing
        log.warning("native window unavailable (%s), opening the browser", type(exc).__name__)
        webbrowser.open(url)
        return 0
    try:
        webview.create_window(title, url, width=1120, height=860, min_size=(420, 560), background_color="#071522",
                              text_select=True)
        kwargs = {"private_mode": False}
        if storage_dir:
            kwargs["storage_path"] = str(storage_dir)
        webview.start(**kwargs)
    except Exception:
        log.exception("native window failed, opening the browser")
        webbrowser.open(url)
    return 0

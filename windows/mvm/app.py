"""Marstek Venus Monitor for Windows — tray application entry point."""
from __future__ import annotations

import argparse
import json
import logging
import os
import queue
import subprocess
import sys
import threading
import time
import webbrowser

from mvm import APP_ID, APP_NAME, VERSION
from mvm.paths import app_paths, ensure_server_on_path, resource_dir

log = logging.getLogger("mvm")
REPO_URL = "https://github.com/jonydhub/marstek-venus-monitor"
TOAST_SPACING_SECONDS = 5
TICK_SECONDS = 2


def _child_command(*args):
    if getattr(sys, "frozen", False):
        return [sys.executable, *args]
    return [sys.executable, "-m", "mvm", *args]


class TrayApp:
    def __init__(self, paths, autostart_flag=False):
        import pystray
        from mvm import autostart
        from mvm.controller import Controller
        self.pystray = pystray
        self.paths = paths
        self.autostart_flag = autostart_flag
        auto = autostart.Autostart(sys.executable) if getattr(sys, "frozen", False) else None
        self.toasts = queue.Queue()
        self.ctl = Controller(paths, autostart=auto, notifier=self.toasts.put, on_change=self.refresh_menu)
        self.icon = None
        self.children = {}
        self.stopping = threading.Event()
        self._shown = None

    # --- windows ---
    def open_window(self, kind, url, title):
        child = self.children.get(kind)
        if child is not None and child.poll() is None:
            child.terminate()  # reopen at the requested page (e.g. «Доступ для телефона»)
        if not self.ctl.cfg.get("native_window", True):
            webbrowser.open(url)
            return
        try:
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            self.children[kind] = subprocess.Popen(_child_command("--window", url, "--title", title),
                                                   creationflags=flags, close_fds=True)
        except OSError:
            log.exception("could not start window process")
            webbrowser.open(url)

    def open_dashboard(self, *_):
        if not self.ctl.configured:
            return self.open_settings()
        self.open_window("dashboard", self.ctl.dashboard_url(), APP_NAME)

    def open_settings(self, *_, anchor=""):
        self.open_window("settings", self.ctl.setup_url(anchor), f"Налаштування · {APP_NAME}")

    # --- menu actions ---
    def toggle_autostart(self, *_):
        state = self.ctl.autostart_state()
        if state["available"]:
            self.ctl.set_autostart(not state["enabled"])

    def toggle_phone(self, *_):
        enabled = not self.ctl.cfg["phone_access"]["enabled"]
        self.ctl.set_phone_access(enabled)
        if enabled:
            self.open_settings(anchor="phone")

    def about(self, *_):
        from mvm.winapi import message_box
        text = (f"{APP_NAME} {VERSION}\n\n"
                "Монітор домашньої батареї Marstek Venus E: заряд, світло, графіки відключень, сповіщення.\n"
                "Програма лише читає дані (Open API по UDP) і ніколи не керує батареєю.\n\n"
                f"Відкритий код, ліцензія MIT: {REPO_URL}\n"
                "Не пов’язано з Marstek, DTEK, YASNO чи обленерго.\n\n"
                f"Дашборд: {self.ctl.dashboard_url()}\n"
                "Журнали: %LOCALAPPDATA%\\MarstekVenusMonitor\\logs")
        threading.Thread(target=message_box, args=(text, f"Про програму · {APP_NAME}"), daemon=True).start()

    def quit(self, *_):
        log.info("exit requested")
        self.stopping.set()
        if self.icon:
            self.icon.stop()

    def build_menu(self):
        item, menu = self.pystray.MenuItem, self.pystray.Menu
        return menu(
            item("Відкрити дашборд", self.open_dashboard, default=True),
            item("Налаштування", self.open_settings),
            menu.SEPARATOR,
            item("Запускати з Windows", self.toggle_autostart,
                 checked=lambda _: self.ctl.autostart_state()["enabled"],
                 enabled=lambda _: self.ctl.autostart_state()["available"]),
            item("Доступ для телефона", self.toggle_phone,
                 checked=lambda _: self.ctl.cfg["phone_access"]["enabled"]),
            menu.SEPARATOR,
            item("Про програму", self.about),
            item("Вийти", self.quit),
        )

    def refresh_menu(self):
        if self.icon:
            try:
                self.icon.update_menu()
            except Exception:
                pass

    # --- tray icon + toasts loop ---
    def update_icon(self, snapshot):
        from mvm import trayicon
        from mvm.snapshot import view
        stale = self.ctl.monitor.is_stale
        configured = self.ctl.configured
        state = trayicon.icon_state(snapshot, stale) if configured else "unknown"
        soc = view(snapshot).soc if snapshot and configured else None
        text = trayicon.icon_text(soc) if configured else "?"
        tip = trayicon.tooltip(snapshot, stale=stale, configured=configured)
        key = (state, text, tip)
        if key == self._shown:
            return
        self._shown = key
        self.icon.icon = trayicon.render_icon(text, trayicon.STATE_COLOURS[state], size=64)
        self.icon.title = tip

    def loop(self, icon):
        icon.visible = True
        last_toast = 0.0
        while not self.stopping.is_set():
            try:
                snapshot = self.ctl.tick()
                self.update_icon(snapshot)
                if time.monotonic() - last_toast >= TOAST_SPACING_SECONDS:
                    try:
                        alert = self.toasts.get_nowait()
                    except queue.Empty:
                        alert = None
                    if alert is not None:
                        icon.notify(alert.text[:250] or alert.title, alert.title[:63])
                        last_toast = time.monotonic()
            except Exception:
                log.exception("tray loop error")
            self.stopping.wait(TICK_SECONDS)

    def run(self):
        from mvm import trayicon
        self.ctl.start()
        write_instance_file(self.paths, self.ctl.server.port)
        image = trayicon.render_icon("?", trayicon.STATE_COLOURS["unknown"], size=64)
        self.icon = self.pystray.Icon(APP_ID, image, APP_NAME, menu=self.build_menu())
        if not self.ctl.configured:
            self.open_settings()
        elif not self.autostart_flag:
            self.open_dashboard()
        try:
            self.icon.run(setup=self.loop)
        finally:
            self.shutdown()

    def shutdown(self):
        self.stopping.set()
        for child in self.children.values():
            if child.poll() is None:
                child.terminate()
        self.ctl.stop()
        try:
            os.remove(self.paths.data_dir / "instance.json")
        except OSError:
            pass
        log.info("stopped")


def write_instance_file(paths, port):
    paths.data_dir.mkdir(parents=True, exist_ok=True)
    (paths.data_dir / "instance.json").write_text(json.dumps({"port": port, "pid": os.getpid()}), "utf-8")


def read_instance_port(paths):
    try:
        return int(json.loads((paths.data_dir / "instance.json").read_text("utf-8"))["port"])
    except (OSError, ValueError, KeyError, TypeError):
        return None


def parse_args(argv):
    p = argparse.ArgumentParser(prog="MarstekVenusMonitor", description=APP_NAME)
    p.add_argument("--window", metavar="URL", help=argparse.SUPPRESS)
    p.add_argument("--title", default=APP_NAME, help=argparse.SUPPRESS)
    p.add_argument("--autostart", action="store_true", help="started by Windows sign-in (no window)")
    p.add_argument("--version", action="store_true", help="print the version and exit")
    args, _unknown = p.parse_known_args(argv)
    return args


def main(argv=None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.version:
        if sys.stdout is not None:  # windowed (no-console) builds have no stdout
            print(f"{APP_NAME} {VERSION}")
        return 0
    paths = app_paths()
    from mvm.logsetup import setup_logging
    from mvm.winapi import SingleInstance, message_box, set_app_user_model_id
    if args.window:
        if not args.window.startswith("http://127.0.0.1:"):
            return 2  # the window only ever shows the local embedded server
        setup_logging(paths.log_dir, filename="window.log")
        from mvm.window import run_window
        return run_window(args.window, args.title, paths.webview_dir)

    instance = SingleInstance()
    if instance.already_running:
        port = read_instance_port(paths)
        if port and not args.autostart:
            subprocess.Popen(_child_command("--window", f"http://127.0.0.1:{port}/", "--title", APP_NAME),
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return 0
    set_app_user_model_id(f"{APP_ID}.Tray")
    holder = {}
    setup_logging(paths.log_dir, secrets_provider=lambda: holder["app"].ctl.secrets() if "app" in holder else [])
    log.info("%s %s starting (frozen=%s, autostart=%s)", APP_NAME, VERSION, getattr(sys, "frozen", False),
             args.autostart)
    ensure_server_on_path()
    try:
        app = TrayApp(paths, autostart_flag=args.autostart)
        holder["app"] = app
        app.run()
    except Exception as exc:
        log.exception("fatal error")
        message_box(f"Не вдалося запустити програму: {exc}\n\nДеталі в журналі %LOCALAPPDATA%\\{APP_ID}\\logs",
                    APP_NAME, error=True)
        return 1
    finally:
        instance.release()
    return 0

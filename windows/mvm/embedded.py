"""Runs the existing read-only server (server/server.py, same code) inside the tray process.

Extra loopback-only routes for the desktop app: /setup (first run + settings) and /api/setup/*.
The battery is still only ever queried with read-only Get methods; POST routes here change local
settings only and are refused unless the request comes from this PC (client IP + Host header + nonce).
"""
from __future__ import annotations

import importlib
import json
import logging
import os
import secrets
import socket
import sys
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from mvm import VERSION, appconfig, localguard
from mvm.paths import ensure_server_on_path, resource_dir

log = logging.getLogger(__name__)
MAX_BODY = 64 * 1024
PORT_FALLBACKS = 10
SETUP_FILES = {"/setup.js": ("setup.js", "text/javascript; charset=utf-8"),
               "/setup.css": ("setup.css", "text/css; charset=utf-8")}


def import_server():
    """Import server/server.py without letting stray env vars or a repo .env configure it."""
    for key in [k for k in os.environ if k.startswith("MARSTEK_")]:
        os.environ.pop(key, None)
    os.environ["MARSTEK_CONFIG"] = os.devnull
    ensure_server_on_path()
    return importlib.import_module("server")


class _HTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = sys.platform != "win32"  # on Windows SO_REUSEADDR would allow port sharing

    def server_bind(self):
        if sys.platform == "win32" and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def make_handler(server_module, controller):
    base = server_module.Handler
    web = resource_dir() / "mvm" / "web"

    class WindowsHandler(base):
        server_version = f"MarstekVenusMonitor-Windows/{VERSION}"

        def is_local(self):
            return localguard.is_local_request(self.client_address[0], self.headers.get("Host"),
                                               self.server.server_address[1])

        def auth_exempt(self):  # the desktop window on this PC needs no token; phones still do
            return self.is_local()

        def _forbidden(self):
            return self.send_json(403, {"error": "local_only"})

        def do_GET(self):
            path = urlparse(self.path).path
            if path == "/setup":
                if not self.is_local():
                    return self._forbidden()
                page = (web / "setup.html").read_text("utf-8").replace("{{NONCE}}", controller.nonce)
                page = page.replace("{{VERSION}}", VERSION)
                return self.send_bytes(200, page.encode(), "text/html; charset=utf-8",
                                       {"Content-Security-Policy": "default-src 'self'; img-src 'self' data:; "
                                        "style-src 'self'; script-src 'self'; frame-ancestors 'none'"})
            if path in SETUP_FILES:
                name, mime = SETUP_FILES[path]
                return self.send_bytes(200, (web / name).read_bytes(), mime)
            if path == "/api/setup/state":
                if not self.is_local():
                    return self._forbidden()
                return self.send_json(200, controller.setup_state())
            return super().do_GET()

        def do_POST(self):
            path = urlparse(self.path).path
            if not path.startswith("/api/setup/"):
                return super().do_POST()
            if not self.is_local() or not localguard.nonce_ok(self.headers.get("X-MVM-Setup"), controller.nonce):
                return self._forbidden()
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                length = -1
            if not 0 <= length <= MAX_BODY:
                return self.send_json(413, {"error": "too_large"})
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                return self.send_json(400, {"error": "bad_json"})
            if not isinstance(body, dict):
                return self.send_json(400, {"error": "bad_json"})
            try:
                status, payload = controller.handle_setup(path.rsplit("/", 1)[-1], body)
            except Exception:
                log.exception("setup action %s failed", path)
                return self.send_json(500, {"error": "internal"})
            return self.send_json(status, payload)

    return WindowsHandler


class EmbeddedServer:
    """Owns the HTTP listener + poller threads of the imported server module."""

    def __init__(self, paths, controller):
        self.paths = paths
        self.controller = controller
        self.srv = import_server()
        self.httpd = None
        self.http_thread = None
        self.bound = None
        self.started = False

    @property
    def port(self):
        return self.bound[1] if self.bound else None

    @property
    def local_url(self):
        return f"http://127.0.0.1:{self.port}/"

    def _settings(self, cfg):
        return appconfig.to_settings(cfg, history_db=str(self.paths.history_db))

    def start(self, cfg, threads=True):
        srv = self.srv
        self._requested_port = cfg["dashboard_port"]
        srv.apply_settings(self._settings(cfg), poll_seconds=cfg["poll_seconds"])
        self.paths.data_dir.mkdir(parents=True, exist_ok=True)
        srv.poller.history = srv.HistoryStore(str(self.paths.history_db))
        srv.poller.history.prune(time.time())
        self._listen(cfg)
        if threads:
            threading.Thread(target=srv.poller.run_outage_refresh, name="outage", daemon=True).start()
            threading.Thread(target=srv.poller.run, name="marstek-poller", daemon=True).start()
        self.started = True

    def _listen(self, cfg):
        bind = "0.0.0.0" if cfg["phone_access"]["enabled"] else "127.0.0.1"
        handler = make_handler(self.srv, self.controller)
        last_error = None
        for port in range(cfg["dashboard_port"], cfg["dashboard_port"] + PORT_FALLBACKS):
            try:
                httpd = _HTTPServer((bind, port), handler)
            except OSError as exc:
                last_error = exc
                continue
            self.httpd, self.bound = httpd, (bind, port)
            self.http_thread = threading.Thread(target=httpd.serve_forever, name="http", daemon=True)
            self.http_thread.start()
            log.info("dashboard listening on %s:%s (phone access %s)", bind, port,
                     "on" if bind != "127.0.0.1" else "off")
            return
        raise OSError(f"no free dashboard port from {cfg['dashboard_port']}: {last_error}")

    def apply(self, cfg):
        self.srv.apply_settings(self._settings(cfg), poll_seconds=cfg["poll_seconds"])
        bind = "0.0.0.0" if cfg["phone_access"]["enabled"] else "127.0.0.1"
        if self.bound and (bind != self.bound[0] or cfg["dashboard_port"] != self._requested_port):
            self._restart_http(cfg)
        self._requested_port = cfg["dashboard_port"]

    _requested_port = None

    def _restart_http(self, cfg):
        old = self.httpd
        old.shutdown()
        old.server_close()
        self.bound = None
        self._listen(cfg)

    def snapshot(self):
        return self.srv.poller.snapshot()

    def stop(self):
        try:
            self.srv.poller.stop()
        except Exception:
            pass
        if self.httpd:
            self.httpd.shutdown()
            self.httpd.server_close()
        history = getattr(self.srv.poller, "history", None)
        if history is not None:
            self.srv.poller.history = None
            try:
                history.close()
            except Exception:
                pass


def new_nonce():
    return secrets.token_urlsafe(24)

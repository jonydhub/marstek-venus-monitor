"""App state shared by the tray UI and the /setup page: config, embedded server, alerts, autostart."""
from __future__ import annotations

import copy
import logging
import threading
from datetime import datetime, timezone

from mvm import APP_NAME, VERSION, appconfig, discovery, phone
from mvm.alerts import ALERT_IDS, ALERT_LABELS, AlertMonitor
from mvm.embedded import EmbeddedServer, new_nonce

log = logging.getLogger(__name__)


class Controller:
    def __init__(self, paths, autostart=None, notifier=None, on_change=None, discover=discovery.discover,
                 probe=discovery.probe, local_ips=lambda: discovery.local_ipv4_addresses()[0]):
        self.paths = paths
        self.autostart = autostart
        self.notifier = notifier or (lambda alert: None)
        self.on_change = on_change or (lambda: None)
        self._discover, self._probe, self._local_ips = discover, probe, local_ips
        self.nonce = new_nonce()
        self.lock = threading.RLock()
        self.discover_lock = threading.Lock()
        self.cfg = appconfig.ensure_token(appconfig.load_config(paths.config_file))
        self.monitor = AlertMonitor(lambda: self.cfg["alerts"])
        self.server = None

    # --- lifecycle ---
    def start(self, threads=True):
        self.server = EmbeddedServer(self.paths, self)
        self.server.start(self.cfg, threads=threads)

    def stop(self):
        if self.server:
            self.server.stop()

    @property
    def configured(self):
        return appconfig.is_configured(self.cfg)

    def secrets(self):
        return [self.cfg["phone_access"]["token"], self.nonce]

    def dashboard_url(self):
        return self.server.local_url if self.server else None

    def setup_url(self, anchor=""):
        return f"{self.server.local_url}setup" + (f"#{anchor}" if anchor else "")

    # --- config changes (apply without restart) ---
    def update_config(self, cfg):
        with self.lock:
            cfg = appconfig.ensure_token(cfg)
            old = self.cfg
            appconfig.save_config(self.paths.config_file, cfg)
            self.cfg = appconfig.load_config(self.paths.config_file)
            if old["battery"] != self.cfg["battery"]:
                self.monitor.reset()
            if self.server:
                self.server.apply(self.cfg)
        log.info("settings applied (battery configured: %s, outage region: %s, phone access: %s)",
                 self.configured, self.cfg["outage"]["region"] or "-", self.cfg["phone_access"]["enabled"])
        self.on_change()

    def set_phone_access(self, enabled, regenerate=False):
        cfg = copy.deepcopy(self.cfg)
        cfg["phone_access"]["enabled"] = bool(enabled)
        if regenerate:
            cfg["phone_access"]["token"] = ""
        self.update_config(cfg)

    def phone_info(self):
        port = self.server.port if self.server else self.cfg["dashboard_port"]
        info = phone.phone_access_info(port, self.cfg["phone_access"]["token"], self._local_ips())
        info["enabled"] = self.cfg["phone_access"]["enabled"]
        info["qr_svg"] = phone.qr_svg(info["pair_url"]) if info["enabled"] and info["pair_url"] else None
        if not info["enabled"]:
            info["token"] = ""
        return info

    def autostart_state(self):
        if not self.autostart or not self.autostart.available:
            return {"available": False, "enabled": False}
        return {"available": True, "enabled": self.autostart.is_enabled()}

    def set_autostart(self, enabled):
        if not self.autostart or not self.autostart.available:
            return False
        self.autostart.set_enabled(bool(enabled))
        log.info("autostart %s", "enabled" if enabled else "disabled")
        self.on_change()
        return True

    # --- polling results -> alerts ---
    def tick(self, now=None):
        """Called by the tray loop; returns the latest snapshot and posts new alerts."""
        if not self.server:
            return None
        snapshot = self.server.snapshot()
        if not self.configured:
            return snapshot
        for alert in self.monitor.observe(snapshot, now or datetime.now(timezone.utc)):
            log.info("alert %s", alert.id)
            self.notifier(alert)
        return snapshot

    # --- /setup page API ---
    def setup_state(self):
        cfg = copy.deepcopy(self.cfg)
        cfg["phone_access"]["token"] = ""  # shown via phone_info only when enabled
        return {
            "app": APP_NAME, "version": VERSION, "configured": self.configured, "config": cfg,
            "alerts": [{"id": i, "label": ALERT_LABELS[i], "enabled": self.cfg["alerts"][i]} for i in ALERT_IDS],
            "autostart": self.autostart_state(), "phone": self.phone_info(),
            "dashboard_url": self.dashboard_url(),
            "min_poll_seconds": appconfig.MIN_POLL_SECONDS,
        }

    def handle_setup(self, action, body):
        if action == "save":
            cfg, errors = appconfig.apply_form(self.cfg, body)
            if errors:
                return 400, {"errors": errors}
            self.update_config(cfg)
            return 200, self.setup_state()
        if action == "discover":
            if not self.discover_lock.acquire(blocking=False):
                return 409, {"errors": ["Пошук уже триває"]}
            try:
                port = body.get("port") or appconfig.DEFAULT_PORT
                devices = self._discover(port=int(port))
            finally:
                self.discover_lock.release()
            return 200, {"devices": devices}
        if action == "test":
            host = str(body.get("host") or "").strip()
            try:
                port = int(body.get("port") or appconfig.DEFAULT_PORT)
            except (TypeError, ValueError):
                port = 0
            if not appconfig.valid_ipv4(host) or not 0 < port < 65536:
                return 400, {"errors": ["Вкажіть IP-адресу (напр. 192.168.0.50) і порт (30000)"]}
            return 200, self._probe(host, port)
        if action == "phone":
            self.set_phone_access(bool(body.get("enabled")), regenerate=bool(body.get("regenerate")))
            return 200, self.setup_state()
        if action == "autostart":
            if not self.set_autostart(bool(body.get("enabled"))):
                return 400, {"errors": ["Автозапуск доступний лише у встановленій програмі для Windows"]}
            return 200, self.setup_state()
        return 404, {"error": "unknown_action"}

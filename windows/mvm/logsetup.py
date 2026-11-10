"""Rotating log file in %LOCALAPPDATA%\\MarstekVenusMonitor\\logs; tokens never reach the log."""
from __future__ import annotations

import logging
import re
from logging.handlers import RotatingFileHandler
from pathlib import Path

_PATTERNS = [
    (re.compile(r"(token=)[^&\s\"']+", re.I), r"\1***"),
    (re.compile(r"(Bearer\s+)[^\s\"']+", re.I), r"\1***"),
]


class RedactingFilter(logging.Filter):
    def __init__(self, secrets_provider=lambda: []):
        super().__init__()
        self.secrets_provider = secrets_provider

    def filter(self, record):
        try:
            message = record.getMessage()
        except Exception:
            return True
        clean = message
        for secret in self.secrets_provider() or []:
            if secret and len(secret) >= 6:
                clean = clean.replace(secret, "***")
        for pattern, repl in _PATTERNS:
            clean = pattern.sub(repl, clean)
        if clean != message:
            record.msg, record.args = clean, ()
        return True


def setup_logging(log_dir, secrets_provider=lambda: [], level=logging.INFO, filename="app.log") -> Path:
    log_dir = Path(log_dir)
    log_dir.mkdir(parents=True, exist_ok=True)
    path = log_dir / filename
    handler = RotatingFileHandler(path, maxBytes=1_000_000, backupCount=3, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(threadName)s %(name)s: %(message)s"))
    handler.addFilter(RedactingFilter(secrets_provider))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
    return path

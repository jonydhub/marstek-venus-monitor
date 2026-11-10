"""Stdlib modules the embedded server (server/*.py, shipped as source) needs at runtime.

The server files are loaded from disk inside the bundle, so PyInstaller cannot see their imports;
importing them here makes the analysis collect them.
"""
import collections  # noqa: F401
import dataclasses  # noqa: F401
import hmac  # noqa: F401
import html  # noqa: F401
import html.parser  # noqa: F401
import http.server  # noqa: F401
import ipaddress  # noqa: F401
import sqlite3  # noqa: F401
import ssl  # noqa: F401
import urllib.error  # noqa: F401
import urllib.parse  # noqa: F401
import urllib.request  # noqa: F401
import zoneinfo  # noqa: F401

try:
    import tzdata  # noqa: F401  (Windows has no system zoneinfo database)
except ImportError:  # pragma: no cover
    pass

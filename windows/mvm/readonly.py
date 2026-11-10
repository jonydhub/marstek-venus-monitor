"""Marstek Open API request codec. READ-ONLY by construction.

Only `Component.GetSomething` methods can be encoded, so no Set/control command can leave the app.
"""
from __future__ import annotations

import json
import re

DISCOVERY_METHOD = "Marstek.GetDevice"
MAX_REQUEST_ID = 65535  # the device echoes ids as uint16
_READ_METHOD = re.compile(r"[A-Za-z]+\.Get[A-Za-z]+")


class ReadOnlyViolation(ValueError):
    pass


def is_read_only(method) -> bool:
    return isinstance(method, str) and _READ_METHOD.fullmatch(method) is not None


def encode_request(request_id: int, method: str) -> bytes:
    if not is_read_only(method):
        raise ReadOnlyViolation(f"only read-only Get methods are allowed: {method!r}")
    if not 0 <= int(request_id) <= MAX_REQUEST_ID:
        raise ValueError(f"request id must fit uint16: {request_id}")
    params = {"ble_mac": "0"} if method == DISCOVERY_METHOD else {"id": 0}
    return json.dumps({"id": int(request_id), "method": method, "params": params}, separators=(",", ":")).encode()

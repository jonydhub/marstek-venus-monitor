"""Setup/config endpoints are for this PC only: loopback client, loopback Host header (DNS rebinding), nonce."""
from __future__ import annotations

import hmac
import ipaddress

LOOPBACK_NAMES = ("127.0.0.1", "localhost", "[::1]")


def is_loopback(client_ip) -> bool:
    try:
        return ipaddress.ip_address(str(client_ip).split("%")[0]).is_loopback
    except ValueError:
        return False


def is_local_request(client_ip, host_header, port) -> bool:
    if not is_loopback(client_ip) or not host_header:
        return False
    host, _, supplied_port = str(host_header).strip().rpartition(":")
    if not host:
        return False
    return host.lower() in LOOPBACK_NAMES and supplied_port == str(port)


def nonce_ok(supplied, expected) -> bool:
    if not supplied or not expected:
        return False
    return hmac.compare_digest(str(supplied).encode(), str(expected).encode())

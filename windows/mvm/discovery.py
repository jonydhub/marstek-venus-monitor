"""Find a Venus battery on the LAN: broadcast Marstek.GetDevice, then a paced unicast sweep of local /24s.

Every host gets at most one small datagram per sweep (the device ignores requests <1 s apart) and the sweep is
bounded (MAX_SUBNETS x /24, MAX_HOSTS probes). Only the read-only discovery method is ever sent.
"""
from __future__ import annotations

import ipaddress
import json
import logging
import socket
import time
from dataclasses import dataclass, field

from mvm.readonly import DISCOVERY_METHOD, encode_request

DEFAULT_PORT = 30000
MAX_PREFIX = 24
MAX_SUBNETS = 4
MAX_HOSTS = 1024
PROBE_INTERVAL_SECONDS = 0.008  # ~125 datagrams/s spread over many hosts, ~2 s per /24
LISTEN_SECONDS = 2.5
_CGNAT = ipaddress.IPv4Network("100.64.0.0/10")  # VPN overlays (e.g. Tailscale): never scanned
_PRIVATE = [ipaddress.IPv4Network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
log = logging.getLogger(__name__)


@dataclass
class ScanPlan:
    hosts: list = field(default_factory=list)
    broadcasts: list = field(default_factory=list)


def _private(ip: ipaddress.IPv4Address) -> bool:
    return any(ip in net for net in _PRIVATE) and ip not in _CGNAT


def plan_scan(local_ips, preferred=None, max_subnets=MAX_SUBNETS, max_hosts=MAX_HOSTS) -> ScanPlan:
    """Pure: which unicast hosts and broadcast addresses to probe for the given local adapter IPs."""
    own, ordered = set(), []
    for text in ([preferred] if preferred else []) + list(local_ips or []):
        try:
            ip = ipaddress.IPv4Address(str(text).strip())
        except ValueError:
            continue
        if not _private(ip):
            continue
        own.add(ip)
        net = ipaddress.IPv4Network(f"{ip}/{MAX_PREFIX}", strict=False)
        if net not in ordered:
            ordered.append(net)
    plan = ScanPlan(broadcasts=["255.255.255.255"])
    for net in ordered[:max_subnets]:
        plan.broadcasts.append(str(net.broadcast_address))
        for host in net.hosts():
            if host in own:
                continue
            if len(plan.hosts) >= max_hosts:
                return plan
            plan.hosts.append(str(host))
    return plan


def local_ipv4_addresses() -> tuple[list, str | None]:
    """(all local IPv4s, the one holding the default route). Stdlib only."""
    ips, primary = [], None
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.connect(("192.0.2.1", 9))  # TEST-NET, nothing is sent
            primary = probe.getsockname()[0]
        finally:
            probe.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.append(info[4][0])
    except OSError:
        pass
    if primary:
        ips.insert(0, primary)
    return list(dict.fromkeys(ips)), primary


def parse_device_reply(raw: bytes, peer_ip: str, port: int):
    try:
        message = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None
    if not isinstance(message, dict) or message.get("error") or not isinstance(message.get("result"), dict):
        return None
    result = message["result"]
    model = str(result.get("device") or str(message.get("src") or "").split("-")[0] or "Marstek")
    firmware = result.get("ver")
    return {"ip": peer_ip, "port": int(port), "model": model,
            "firmware": firmware if isinstance(firmware, int) else None}


def _udp_socket():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.bind(("0.0.0.0", 0))
    return sock


def discover(port=DEFAULT_PORT, local_ips=None, preferred=None, sock_factory=_udp_socket, sleep=time.sleep,
             listen_seconds=LISTEN_SECONDS, clock=time.monotonic, interval=PROBE_INTERVAL_SECONDS):
    """Return [{ip, port, model, firmware}] for every battery that answered."""
    if local_ips is None:
        local_ips, preferred = local_ipv4_addresses()
    plan = plan_scan(local_ips, preferred=preferred)
    found = {}
    sock = sock_factory()
    request_id = 1
    payload = encode_request(request_id, DISCOVERY_METHOD)

    def drain():
        while True:
            try:
                raw, peer = sock.recvfrom(4096)
            except (TimeoutError, BlockingIOError, socket.timeout):
                return
            except OSError:
                return
            device = parse_device_reply(raw, peer[0], port)
            if device and device["ip"] not in found:
                found[device["ip"]] = device

    try:
        sock.settimeout(0.001)
        for address in plan.broadcasts:
            try:
                sock.sendto(payload, (address, port))
            except OSError as exc:  # e.g. no route for a stale adapter
                log.debug("broadcast %s failed: %s", address, exc)
        sleep(0.5)
        drain()
        for host in plan.hosts:
            if host in found:
                continue
            try:
                sock.sendto(payload, (host, port))
            except OSError:
                pass
            drain()
            sleep(interval)
        deadline = clock() + listen_seconds
        sock.settimeout(0.2)
        while clock() < deadline:
            drain()
    finally:
        sock.close()
    log.info("discovery: %d probes, %d battery(ies) answered", len(plan.hosts), len(found))
    return sorted(found.values(), key=lambda d: tuple(int(p) for p in d["ip"].split(".")))


def probe(host: str, port=DEFAULT_PORT, timeout=2.0, sock_factory=None, sleep=time.sleep):
    """«Перевірити з'єднання»: GetDevice then Bat.GetStatus, paced >1 s apart. Returns a dict with ok/error."""
    sock = (sock_factory or _udp_socket)()
    out = {"ok": False, "ip": host, "port": int(port)}
    try:
        sock.settimeout(timeout)
        for request_id, method in ((11, DISCOVERY_METHOD), (12, "Bat.GetStatus")):
            if request_id == 12:
                sleep(1.1)
            sock.sendto(encode_request(request_id, method), (host, int(port)))
            reply = None
            end = time.monotonic() + timeout
            while time.monotonic() < end:
                try:
                    raw, peer = sock.recvfrom(8192)
                except (TimeoutError, socket.timeout):
                    break
                if peer[0] != host:
                    continue
                try:
                    msg = json.loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, ValueError):
                    continue
                if isinstance(msg, dict) and msg.get("id") == request_id:
                    reply = msg
                    break
            if reply is None:
                out["error"] = "Батарея не відповіла. Перевірте IP, порт і що Open API увімкнено."
                return out
            if reply.get("error"):
                out["error"] = "Батарея відповіла помилкою: " + str((reply["error"] or {}).get("message", ""))[:80]
                return out
            result = reply.get("result") or {}
            if method == DISCOVERY_METHOD:
                out["model"] = result.get("device") or "Marstek"
                out["firmware"] = result.get("ver")
            else:
                out["soc"] = result.get("soc")
                out["bat_capacity_wh"] = result.get("bat_capacity")
        out["ok"] = True
        return out
    except OSError as exc:
        out["error"] = f"Мережева помилка: {exc.strerror or exc}"
        return out
    finally:
        sock.close()

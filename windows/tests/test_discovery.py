import json
import unittest

from tests import _path  # noqa: F401
from mvm import discovery, readonly


class PlannerTests(unittest.TestCase):
    def test_single_adapter_scans_its_slash24_without_itself(self):
        plan = discovery.plan_scan(["192.168.0.23"])
        self.assertEqual(253, len(plan.hosts))
        self.assertNotIn("192.168.0.23", plan.hosts)
        self.assertIn("192.168.0.1", plan.hosts)
        self.assertIn("192.168.0.254", plan.hosts)
        self.assertIn("255.255.255.255", plan.broadcasts)
        self.assertIn("192.168.0.255", plan.broadcasts)

    def test_public_cgnat_loopback_and_link_local_are_ignored(self):
        plan = discovery.plan_scan(["8.8.8.8", "100.101.102.103", "127.0.0.1", "169.254.3.4", "bogus"])
        self.assertEqual([], plan.hosts)
        self.assertEqual(["255.255.255.255"], plan.broadcasts)

    def test_subnets_are_deduplicated_and_bounded(self):
        ips = ["10.0.%d.5" % i for i in range(10)] + ["10.0.0.9"]
        plan = discovery.plan_scan(ips, max_subnets=2, max_hosts=300)
        self.assertLessEqual(len(plan.hosts), 300)
        self.assertEqual(2, len({h.rsplit(".", 1)[0] for h in plan.hosts}))
        self.assertNotIn("10.0.0.5", plan.hosts)
        self.assertNotIn("10.0.0.9", plan.hosts)

    def test_preferred_adapter_goes_first(self):
        plan = discovery.plan_scan(["172.16.5.2", "192.168.0.23"], preferred="192.168.0.23")
        self.assertTrue(plan.hosts[0].startswith("192.168.0."))

    def test_probe_rate_is_paced(self):
        self.assertGreaterEqual(discovery.PROBE_INTERVAL_SECONDS, 0.005)
        self.assertLessEqual(discovery.MAX_HOSTS, 1024)


class ReplyParsingTests(unittest.TestCase):
    def test_get_device_reply(self):
        raw = json.dumps({"id": 7, "src": "VenusE-abc", "result": {"device": "VenusE 3.0", "ver": 144,
                                                                     "ble_mac": "0", "ip": "192.0.2.10"}}).encode()
        dev = discovery.parse_device_reply(raw, "192.0.2.10", 30000)
        self.assertEqual({"ip": "192.0.2.10", "port": 30000, "model": "VenusE 3.0", "firmware": 144}, dev)

    def test_garbage_and_errors_are_ignored(self):
        self.assertIsNone(discovery.parse_device_reply(b"\xff\x00", "192.0.2.10", 30000))
        self.assertIsNone(discovery.parse_device_reply(b'{"id":1,"error":{"code":-1}}', "192.0.2.10", 30000))
        self.assertIsNone(discovery.parse_device_reply(b"[]", "192.0.2.10", 30000))


class FakeSocket:
    def __init__(self, replies):
        self.sent, self.replies = [], list(replies)

    def setsockopt(self, *a):
        pass

    def settimeout(self, t):
        pass

    def bind(self, addr):
        pass

    def sendto(self, payload, addr):
        self.sent.append((json.loads(payload), addr))

    def recvfrom(self, n):
        if not self.replies:
            raise TimeoutError
        return self.replies.pop(0)

    def close(self):
        pass


class DiscoverTests(unittest.TestCase):
    def test_discover_sends_only_get_device_and_deduplicates(self):
        reply = json.dumps({"id": 1, "result": {"device": "VenusE"}}).encode()
        sock = FakeSocket([(reply, ("192.168.0.50", 30000)), (reply, ("192.168.0.50", 30000))])
        found = discovery.discover(local_ips=["192.168.0.23"], sock_factory=lambda: sock, sleep=lambda s: None,
                                   listen_seconds=0, clock=iter(range(0, 10_000)).__next__)
        self.assertEqual(["192.168.0.50"], [d["ip"] for d in found])
        self.assertTrue(sock.sent)
        self.assertEqual({"Marstek.GetDevice"}, {p["method"] for p, _ in sock.sent})
        self.assertEqual({"ble_mac": "0"}, sock.sent[0][0]["params"])
        # every host probed at most once (the battery needs >=1 s between requests)
        unicast = [a for _, a in sock.sent if not a[0].endswith(".255")]
        self.assertEqual(len(unicast), len(set(unicast)))


class ReadOnlyCodecTests(unittest.TestCase):
    def test_encode_refuses_control_methods(self):
        for method in ("ES.SetMode", "Bat.Set", "Marstek.Reboot", "ES.GetMode x"):
            with self.assertRaises(readonly.ReadOnlyViolation):
                readonly.encode_request(1, method)

    def test_encode_get_and_discovery_params(self):
        self.assertEqual({"id": 5, "method": "ES.GetStatus", "params": {"id": 0}},
                         json.loads(readonly.encode_request(5, "ES.GetStatus")))
        self.assertEqual({"ble_mac": "0"}, json.loads(readonly.encode_request(5, "Marstek.GetDevice"))["params"])

    def test_ids_must_fit_uint16(self):
        with self.assertRaises(ValueError):
            readonly.encode_request(70000, "ES.GetStatus")

    def test_source_tree_contains_no_control_method_literals(self):
        import re
        from pathlib import Path
        root = Path(_path.WINDOWS) / "mvm"
        pattern = re.compile(r"[\"'][A-Za-z]+\.Set[A-Za-z]*[\"']")
        hits = [str(p) for p in root.rglob("*.*") if p.suffix in (".py", ".js", ".html")
                and pattern.search(p.read_text("utf-8"))]
        self.assertEqual([], hits)


if __name__ == "__main__":
    unittest.main()

import unittest

import server


class ParseBindsTests(unittest.TestCase):
    def test_default(self):
        self.assertEqual(server.parse_binds("", 8767), [("127.0.0.1", 8767)])

    def test_multiple_with_ports(self):
        self.assertEqual(server.parse_binds("127.0.0.1, 10.0.0.5:8768", 8767),
                         [("127.0.0.1", 8767), ("10.0.0.5", 8768)])

    def test_ipv6_and_dedupe(self):
        self.assertEqual(server.parse_binds("[::1]:9000,[::1]:9000,0.0.0.0", 8767),
                         [("::1", 9000), ("0.0.0.0", 8767)])

    def test_bad_port(self):
        with self.assertRaises(ValueError):
            server.parse_binds("127.0.0.1:70000", 8767)


if __name__ == "__main__":
    unittest.main()

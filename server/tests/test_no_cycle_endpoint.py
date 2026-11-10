import unittest
from unittest.mock import patch

from server import METHODS, poller


class ReadOnlyStatusPayloadTests(unittest.TestCase):
    def test_status_does_not_publish_a_synthetic_cycle_counter(self):
        def response(method):
            if method == "Bat.GetStatus":
                return {"ok": True, "data": {"bat_capacity": 5000.0, "rated_capacity": 5120.0}}
            return {"ok": True, "data": {}}

        with patch.object(poller, "query", side_effect=response), \
                patch("server.time.sleep"):
            poller.poll_once()

        self.assertNotIn("battery_cycles", poller.snapshot())
        self.assertEqual(set(METHODS), set(poller.snapshot()["methods"]))


if __name__ == "__main__":
    unittest.main()

"""history.counters — «Лічильники»: today / stats window / total since the server keeps history (day_totals table)."""
import os
import tempfile
import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from history import HistoryStore, counters as _counters


def counters(*args, **kwargs) -> dict:
    result = _counters(*args, **kwargs)
    assert result is not None
    return result


def day(date, charge=0.0, discharge=0.0, outage=0.0, off=0, n=0, today=False):
    return {"date": date, "is_today": today, "charge_wh": charge, "discharge_wh": discharge,
            "outage_energy_wh": outage, "off_seconds": off, "outages": n}


class CountersTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.store = HistoryStore(os.path.join(self.dir.name, "h.db"))

    def tearDown(self):
        self.store.close()
        self.dir.cleanup()

    def test_today_window_total(self):
        days = [day("2026-11-26", 840, 720, 560, 4560, 2, today=True), day("2026-11-25", 230, 410, 60, 3300, 2),
                day("2026-11-24", 1000, 500, 530, 9240, 1)]
        c = counters(self.store, days)
        self.assertEqual(c["since"], "2026-11-24")
        self.assertEqual(c["today"]["charge_wh"], 840)
        self.assertEqual(c["window"], {"charge_wh": 2070, "discharge_wh": 1630, "outage_energy_wh": 1150,
                                       "off_seconds": 17100, "outages": 5})
        self.assertEqual(c["total"], c["window"])
        # today is never stored — it changes until midnight
        self.assertEqual([d["date"] for d in self.store.day_totals()], ["2026-11-24", "2026-11-25"])

    def test_total_survives_the_window(self):
        counters(self.store, [day("2026-11-20", today=True), day("2026-11-19", 500, 100, 50, 600, 1)])
        # a week later 19.11 is out of the stats window, but still counted in the total
        days = [day("2026-11-27", 100, today=True), day("2026-11-26", 200, 300, 0, 0, 0)]
        c = counters(self.store, days)
        self.assertEqual(c["since"], "2026-11-19")
        self.assertEqual(c["window"]["charge_wh"], 300)
        self.assertEqual(c["total"]["charge_wh"], 800)
        self.assertEqual(c["total"]["outages"], 1)

    def test_day_recomputed_is_replaced_not_added(self):
        counters(self.store, [day("2026-11-26", today=True), day("2026-11-25", 100)])
        c = counters(self.store, [day("2026-11-26", today=True), day("2026-11-25", 150)])
        self.assertEqual(c["total"]["charge_wh"], 150)

    def test_days_before_the_first_sample_are_not_stored(self):
        # history started on 25.11 at 18:20 Kyiv — 24.11 and earlier are empty placeholders
        first = datetime(2026, 11, 25, 18, 20, 20, tzinfo=ZoneInfo("Europe/Kyiv")).timestamp()
        c = counters(self.store, [day("2026-11-26", 10, today=True), day("2026-11-25", 5), day("2026-11-24")],
                     first_sample_ts=first)
        self.assertEqual(c["since"], "2026-11-25")
        self.assertEqual(c["window_days"], 2)
        self.assertEqual([d["date"] for d in self.store.day_totals()], ["2026-11-25"])

    def test_empty(self):
        self.assertIsNone(_counters(self.store, []))


if __name__ == "__main__":
    unittest.main()

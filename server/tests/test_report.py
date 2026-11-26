"""report.py — «Звіт» week / month totals, previous period, Telegram text."""
import unittest
from datetime import date

from report import period_range, report, title


def day(d, charge=0.0, discharge=0.0, outage=0.0, off=0, n=0):
    return {"date": d, "charge_wh": charge, "discharge_wh": discharge, "outage_energy_wh": outage,
            "off_seconds": off, "outages": n}


DAYS = [  # history starts on Fri 20.11
    day("2026-11-20", 1631.7, 1403.0, 1220.0, 25860, 3), day("2026-11-21", 2192.0, 633.3, 530.0, 11880, 1),
    day("2026-11-22", 952.7, 246.4), day("2026-11-23", 968.8, 261.7),
    day("2026-11-24", 1368.8, 691.9, 530.0, 9240, 1), day("2026-11-25", 976.3, 287.8, 60.0, 3540, 2),
    day("2026-11-26", 843.9, 1718.5, 1462.4, 9387, 2),
]
TODAY = date(2026, 11, 26)  # Thursday


class RangeTests(unittest.TestCase):
    def test_week(self):
        self.assertEqual(period_range("week", TODAY), (date(2026, 11, 23), TODAY))
        self.assertEqual(period_range("week", TODAY, 1), (date(2026, 11, 16), date(2026, 11, 22)))

    def test_month(self):
        self.assertEqual(period_range("month", TODAY), (date(2026, 11, 1), TODAY))
        self.assertEqual(period_range("month", TODAY, 1), (date(2026, 10, 1), date(2026, 10, 31)))
        self.assertEqual(period_range("month", date(2027, 1, 15), 1), (date(2026, 12, 1), date(2026, 12, 31)))
        self.assertEqual(period_range("month", date(2027, 3, 5), 1), (date(2027, 2, 1), date(2027, 2, 28)))


class ReportTests(unittest.TestCase):
    def test_current_week(self):
        r = report(DAYS, today=TODAY, period="week")
        cur, prev = r["current"], r["previous"]
        self.assertEqual((cur["start"], cur["end"], cur["covered_days"], cur["complete"]), ("2026-11-23", "2026-11-26", 4, True))
        self.assertEqual(cur["off_seconds"], 22167)
        self.assertEqual(cur["outages"], 5)
        self.assertEqual(cur["days_with_outages"], 3)
        self.assertEqual(cur["charge_wh"], 4157.8)
        self.assertEqual(cur["charge_cost_uah"], 17.96)
        self.assertEqual((prev["covered_days"], prev["complete"]), (3, False))  # 20–22.11 of 16–22.11
        self.assertEqual(prev["charge_wh"], 4776.4)

    def test_last_full_week_text(self):
        # the Monday report: offset 1 = the week that just ended
        r = report(DAYS + [day("2026-11-30", 100.0)], today=date(2026, 11, 30), period="week", offset=1)
        self.assertEqual(title(r), "Тиждень 23–29.11")
        text = r["text"]
        self.assertIn("Без світла: 6 год 9 хв — 5 відключень, у 3 з 7 дн.", text)
        self.assertIn("Заряджено з мережі: 4,16 кВт·год ≈ 17,96 грн", text)
        self.assertIn("Порівняно з минулим тижнем (неповним, 3 дн.):", text)
        self.assertIn("• без світла −4 год 20 хв", text)

    def test_month_title_with_partial_history(self):
        r = report(DAYS, today=TODAY, period="month")
        self.assertEqual(title(r), "Листопад 2026 (дані з 20 листопада)")
        self.assertEqual(r["current"]["covered_days"], 7)
        self.assertEqual(r["previous"]["covered_days"], 0)
        self.assertNotIn("Порівняно", r["text"])

    def test_no_data(self):
        r = report([], today=TODAY, period="week")
        self.assertEqual(r["current"]["covered_days"], 0)
        self.assertTrue(r["text"].endswith("ще немає вимірів."))

    def test_week_without_outages(self):
        r = report([day("2026-11-23", 500.0), day("2026-11-24", 400.0)], today=date(2026, 11, 24))
        self.assertIn("Без відключень", r["text"])


if __name__ == "__main__":
    unittest.main()

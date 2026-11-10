"""Direct oblenergo parsers (ported from hukpo/ua-outages, MIT) against pages captured 2026-11-10 ~15:40 Kyiv
from a Ukrainian connection (Chernivtsi fixture: footer app-store badges removed)."""
import json
import unittest
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from netcache import CachedJsonClient
from oblenergo import (
    CHERNIVTSI_NEXT_URL,
    CHERNIVTSI_URL,
    KHARKIV_URL,
    POLTAVA_URL,
    ZAPORIZHZHIA_URL,
    ChernivtsiFetcher,
    KharkivFetcher,
    PoltavaFetcher,
    ZaporizhzhiaFetcher,
    find_ua_date,
    parse_chernivtsi,
    parse_group_lines,
    parse_kharkiv,
    parse_poltava,
    parse_zaporizhzhia,
)

KYIV = ZoneInfo("Europe/Kyiv")
FIXTURES = Path(__file__).resolve().parent / "fixtures"
NOW = datetime(2026, 11, 10, 15, 40, tzinfo=KYIV)
QUEUES = ["1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2"]


def text(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


def win(start, end, day="2026-11-10"):
    return (f"{day}T{start}:00+02:00", f"{day}T{end}:00+02:00")


class Client:
    def __init__(self, routes):
        self.routes, self.urls = routes, []

    def get(self, url):
        self.urls.append(url)
        return self.routes[url]


class HelperTests(unittest.TestCase):
    def test_find_ua_date(self):
        today = date(2026, 11, 10)
        self.assertEqual(find_ua_date("ГПВ на 11 листопада", today), date(2026, 11, 11))
        self.assertEqual(find_ua_date("ОНОВЛЕНО ГПВ НА 10 ЛИСТОПАДА", today), date(2026, 11, 10))
        self.assertEqual(find_ua_date("графік 09.11.2026", today), date(2026, 11, 9))
        self.assertEqual(find_ua_date("31 грудня", date(2027, 1, 1)), date(2026, 12, 31))
        self.assertIsNone(find_ua_date("черга 3.1 о 21:25", today))

    def test_group_lines(self):
        lines = parse_group_lines("<p>1.1 00:00-01:30; 06:00-12:00<br>1.2: 19:00 – 24:00</p><p>Групи 2.1, 2.2: з 08:00 до 10:00</p>")
        self.assertEqual(lines["1.1"], [(0, 90, 0), (360, 720, 0)])
        self.assertEqual(lines["1.2"], [(1140, 1440, 0)])
        self.assertEqual(lines["2.1"], [(480, 600, 0)])
        self.assertEqual(lines["2.2"], [(480, 600, 0)])
        self.assertEqual(parse_group_lines("ГПВ в обсязі 2.5 черги з 08:00 до 22:00"), {})


class KharkivTests(unittest.TestCase):
    def test_posts_to_schedule(self):
        entry = parse_kharkiv(json.loads(text("oblenergo_kharkiv.json")), NOW)
        self.assertEqual(entry["groups"], QUEUES)
        self.assertEqual(entry["days"]["2026-11-10"]["1.1"], {"s": "s", "i": [[0, 90, 0], [360, 720, 0], [930, 1320, 0]]})
        self.assertEqual(entry["updatedAt"], "2026-11-09T20:23:26+02:00")

    def test_fetcher(self):
        client = Client({KHARKIV_URL: json.loads(text("oblenergo_kharkiv.json"))})
        result = KharkivFetcher("1.1", client=client).fetch(now=NOW)
        self.assertEqual([s["date"] for s in result["schedules"]], ["2026-11-10"])
        self.assertEqual(result["schedules"][0]["groups"]["1.1"],
                         [win("00:00", "01:30"), win("06:00", "12:00"), win("15:30", "22:00")])
        self.assertEqual(result["maybe"], [])
        self.assertIn("filter%5Bt%5D%5Bcondition%5D%5Bvalue%5D=%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B8%D0%BD%D0%BD",
                      client.urls[0])
        with self.assertRaises(LookupError):
            KharkivFetcher("9.9", client=client).fetch(now=NOW)

    def test_no_data_list_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_kharkiv({"errors": []}, NOW)


class ZaporizhzhiaTests(unittest.TestCase):
    def test_newest_post_per_day_wins(self):
        entry = parse_zaporizhzhia(text("oblenergo_zaporizhzhia.html"), NOW)
        self.assertEqual(entry["groups"], QUEUES)
        # 14:40 correction beats the 11:33 one for 1.2 (adds 14:30–18:00)
        self.assertEqual(entry["days"]["2026-11-10"]["1.2"]["i"], [[330, 540, 0], [870, 1080, 0], [1410, 1440, 0]])
        self.assertIsNone(entry["updatedAt"])

    def test_fetcher(self):
        result = ZaporizhzhiaFetcher("1.1", client=Client({ZAPORIZHZHIA_URL: text("oblenergo_zaporizhzhia.html")})) \
            .fetch(now=NOW)
        self.assertEqual(result["schedules"][0]["groups"]["1.1"],
                         [win("10:00", "15:00"), ("2026-11-10T19:00:00+02:00", "2026-11-11T00:00:00+02:00")])

    def test_page_without_posts_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_zaporizhzhia("<html></html>", NOW)


class PoltavaTests(unittest.TestCase):
    def test_cells_match_the_page_period_list(self):
        entry = parse_poltava(text("oblenergo_poltava.html"), NOW)
        self.assertEqual(entry["groups"], QUEUES)
        day = entry["days"]["2026-11-10"]["1.1"]["i"]
        # page text: «1.1 — 04:00–07:30, 10:00–14:00, 16:00–20:00, 22:00–24:00»; s3 cells = switching (maybe)
        self.assertEqual([iv[:2] for iv in day if iv[2] == 0], [[240, 450], [600, 840], [960, 1200], [1320, 1440]])
        self.assertIn([0, 30, 1], day)

    def test_fetcher_splits_off_and_maybe(self):
        result = PoltavaFetcher("1.1", client=Client({POLTAVA_URL: text("oblenergo_poltava.html")})).fetch(now=NOW)
        self.assertEqual(result["schedules"][0]["groups"]["1.1"][0], win("04:00", "07:30"))
        self.assertEqual(result["maybe"][0], win("00:00", "00:30"))


class ChernivtsiTests(unittest.TestCase):
    def test_page(self):
        entry = parse_chernivtsi([text("oblenergo_chernivtsi.html")])
        self.assertEqual(entry["groups"], [str(n) for n in range(1, 13)])
        self.assertEqual(entry["days"]["2026-11-10"]["1"]["i"],
                         [[420, 540, 0], [540, 570, 1], [780, 900, 0], [900, 930, 1], [1020, 1200, 0], [1200, 1230, 1]])
        self.assertEqual(entry["updatedAt"], "2026-11-10T15:30:00+02:00")

    def test_tomorrow_only_fetched_when_linked(self):
        page = text("oblenergo_chernivtsi.html")
        client = Client({CHERNIVTSI_URL: page})
        result = ChernivtsiFetcher("1", client=client).fetch(now=NOW)
        self.assertEqual(client.urls, [CHERNIVTSI_URL])
        self.assertEqual(result["schedules"][0]["groups"]["1"][0], win("07:00", "09:00"))
        tomorrow = page.replace("10.11.2026", "11.11.2026")
        client = Client({CHERNIVTSI_URL: page.replace("</body>", '<a href="/shutdowns/?next">Завтра</a></body>'),
                         CHERNIVTSI_NEXT_URL: tomorrow})
        result = ChernivtsiFetcher("1", client=client).fetch(now=NOW)
        self.assertEqual([s["date"] for s in result["schedules"]], ["2026-11-10", "2026-11-11"])

    def test_all_on_page_means_no_outages(self):
        page = text("oblenergo_chernivtsi.html").replace("<o>в</o>", "<u>з</u>").replace("<s>мз</s>", "<u>з</u>")
        self.assertEqual(parse_chernivtsi([page])["days"]["2026-11-10"]["1"], {"s": "n", "i": []})


class TextClientTests(unittest.TestCase):
    def test_as_text_returns_body_and_sends_conditional_headers(self):
        calls = []

        def opener(url, headers, timeout):
            calls.append(dict(headers))
            if len(calls) == 1:
                return 200, {"ETag": '"x"', "Last-Modified": "Tue, 10 Nov 2026 12:47:35 GMT"}, "<p>ГПВ</p>".encode()
            return 304, {}, b""

        clock = [0.0]
        client = CachedJsonClient(opener=opener, ttl=600, clock=lambda: clock[0], accept="text/html", as_text=True)
        self.assertEqual(client.get("https://example.test/"), "<p>ГПВ</p>")
        clock[0] = 300
        client.get("https://example.test/")
        self.assertEqual(len(calls), 1)  # within 10 minutes: cached
        clock[0] = 700
        self.assertEqual(client.get("https://example.test/"), "<p>ГПВ</p>")
        self.assertEqual(calls[1]["If-None-Match"], '"x"')
        self.assertEqual(calls[1]["If-Modified-Since"], "Tue, 10 Nov 2026 12:47:35 GMT")
        self.assertIn("marstek-venus-monitor", calls[1]["User-Agent"])


if __name__ == "__main__":
    unittest.main()

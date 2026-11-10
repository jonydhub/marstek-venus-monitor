"""Direct official schedules from regional grid operators (oblenergos) that have no open API we
already use: Kharkiv, Poltava, Zaporizhzhia, Chernivtsi.

Parsers ported from hukpo/ua-outages (MIT License, Copyright (c) Pavlo Huk,
https://github.com/hukpo/ua-outages): src/providers/{kharkiv,poltava,zaporizhzhia,chernivtsi}.ts and
the shared helpers src/providers/posts.ts, src/providers/ua-text.ts, src/schedule.ts.
That project's hosted aggregate cannot reach these four sites from abroad; read directly from a
Ukrainian connection they answer normally.

Every parser returns a ua-outages-style region entry
  {"updatedAt", "groups", "days": {YYYY-MM-DD: {group: {"s": "s"|"n"|"u", "i": [[start, end, kind]]}}}}
(kind 0 = off, 1 = maybe; minutes from Kyiv midnight) so the result goes through the same
`uaoutages.parse_region_days` as the aggregate.
"""
from __future__ import annotations

import re
import urllib.parse
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from netcache import CachedJsonClient
from uaoutages import parse_region_days

KYIV = ZoneInfo("Europe/Kyiv")
OFF, MAYBE = 0, 1

MONTHS = {"січня": 1, "лютого": 2, "березня": 3, "квітня": 4, "травня": 5, "червня": 6, "липня": 7,
          "серпня": 8, "вересня": 9, "жовтня": 10, "листопада": 11, "грудня": 12}
_NAMED_DATE = re.compile(r"(\d{1,2})\s+(" + "|".join(MONTHS) + r")(?:\s+(\d{4}))?")
_NUMERIC_DATE = re.compile(r"\b(\d{1,2})\.(\d{2})(?:\.(\d{4}))?\b")
_TIME = r"(\d{1,2}[:.]\d{2})"
_RANGE = re.compile(r"(?:з\s*)?" + _TIME + r"\s*(?:[-–—−]|до|по)\s*" + _TIME, re.IGNORECASE)
_FIRST_TIME = re.compile(r"\d{1,2}[:.]\d{2}\s*(?:[-–—−]|до|по)", re.IGNORECASE)
_GROUP = re.compile(r"(?<![\d:.])(\d{1,2})\s*[.,]\s*([12])(?!\d)")
_LINE_SPLIT = re.compile(r"\n|<br\s*/?>|</p>|</li>|</div>", re.IGNORECASE)
_TAG = re.compile(r"<[^>]+>")


# --- shared helpers (ua-text.ts, schedule.ts, posts.ts) ---

def kyiv_today(now: datetime) -> date:
    return now.astimezone(KYIV).date()


def find_ua_date(text: str, today: date):
    """First date in the text ("5 листопада 2026", "05.11", ...); a year-less date goes to the
    year that puts it closest to `today`."""
    lower = (text or "").lower()
    named = _NAMED_DATE.search(lower)
    numeric = next((m for m in _NUMERIC_DATE.finditer(lower)
                    if 1 <= int(m.group(2)) <= 12 and 1 <= int(m.group(1)) <= 31), None)
    if named and (not numeric or named.start() <= numeric.start()):
        day, month, year = int(named.group(1)), MONTHS[named.group(2)], named.group(3)
    elif numeric:
        day, month, year = int(numeric.group(1)), int(numeric.group(2)), numeric.group(3)
    else:
        return None
    candidates = [int(year)] if year else [today.year - 1, today.year, today.year + 1]
    dates = []
    for y in candidates:
        try:
            dates.append(date(y, month, day))
        except ValueError:
            continue
    return min(dates, key=lambda d: abs((d - today).days)) if dates else None


def parse_clock(text: str):
    m = re.fullmatch(r"\s*(\d{1,2})\s*[:.]\s*(\d{2})\s*", text)
    if not m:
        return None
    h, mins = int(m.group(1)), int(m.group(2))
    if mins > 59 or h > 24 or (h == 24 and mins != 0):
        return None
    return h * 60 + mins


def find_time_ranges(text: str):
    out = []
    for m in _RANGE.finditer(text):
        a, b = parse_clock(m.group(1)), parse_clock(m.group(2))
        if a is None or b is None:
            continue
        if b in (1439, 0) or b <= a:  # "…–23:59" / "…–00:00" / overnight end at midnight
            b = 1440
        out.append((a, b))
    return out


def normalize_group(raw: str):
    s = raw.lower()
    sub = re.search(r"(\d+)\s*(?:черга|queue)?\D{0,12}?(\d+)\s*під", s)
    if sub:
        return f"{int(sub.group(1))}.{int(sub.group(2))}"
    m = re.search(r"(\d+)\s*[.,_-]\s*(\d+)", s) or re.search(r"(\d+)", s)
    if not m:
        return None
    return str(int(m.group(1))) if m.lastindex == 1 else f"{int(m.group(1))}.{int(m.group(2))}"


def group_key(group: str):
    return [int(p) if p.isdigit() else 0 for p in group.split(".")]


def parse_group_lines(text: str, kind=OFF):
    """"1.1 00:00-03:00; 08:00-13:30" / "1.1: 10:00 – 15:00" lines -> {group: [(start, end, kind)]}."""
    out = {}
    for raw in _LINE_SPLIT.split(text or ""):
        line = _TAG.sub(" ", raw).replace("&nbsp;", " ").strip()
        if not line:
            continue
        first = _FIRST_TIME.search(line)
        head = line if first is None else line[:first.start()]
        groups = [g for g in (normalize_group(f"{m.group(1)}.{m.group(2)}") for m in _GROUP.finditer(head)) if g]
        if not groups:
            continue
        ranges = [] if first is None else find_time_ranges(line[first.start():])
        for g in groups:
            out.setdefault(g, []).extend((a, b, kind) for a, b in ranges)
    return out


def merge_slots(slots):
    """Minute slots of one day -> sorted, merged [start, end, kind]; off beats maybe on overlap."""
    level = [0] * 1440  # 0 on, 1 maybe, 2 off
    for start, end, kind in slots:
        value = 2 if kind == OFF else 1
        for i in range(max(0, int(start)), min(1440, int(end))):
            if level[i] < value:
                level[i] = value
    out, i = [], 0
    while i < 1440:
        if not level[i]:
            i += 1
            continue
        j = i
        while j < 1440 and level[j] == level[i]:
            j += 1
        out.append([i, j, OFF if level[i] == 2 else MAYBE])
        i = j
    return out


def cells_to_slots(cells):
    """Equal cells (24 hourly / 48 half-hourly), None = power on -> minute slots."""
    size = 1440 // len(cells)
    return [(i * size, (i + 1) * size, kind) for i, kind in enumerate(cells) if kind is not None]


def _day(slots):
    return {"s": "s", "i": merge_slots(slots)}


def posts_to_entry(posts, now: datetime) -> dict:
    """News posts [(title, body, published_at datetime|None)] -> entry. Newest post per date wins;
    posts without group lines are ignored; only yesterday and later is kept."""
    today = kyiv_today(now)
    by_date = {}
    for title, body, published in posts:
        ref = kyiv_today(published) if published else today
        day = find_ua_date(title, ref) or find_ua_date(_TAG.sub(" ", body or "")[:600], ref)
        if day is None or day < today - timedelta(days=1) or abs((day - ref).days) > 3:
            continue
        groups = parse_group_lines(body)
        if not groups:
            continue
        at = published.timestamp() if published else 0.0
        if day not in by_date or at > by_date[day][0]:
            by_date[day] = (at, groups, published)
    all_groups = sorted({g for _, groups, _ in by_date.values() for g in groups}, key=group_key)
    days = {}
    for day in sorted(by_date):
        groups = by_date[day][1]
        days[day.isoformat()] = {g: (_day(groups[g]) if g in groups else {"s": "u", "i": []}) for g in all_groups}
    newest = max((p for _, _, p in by_date.values() if p), default=None)
    return {"updatedAt": newest.astimezone(KYIV).isoformat(timespec="seconds") if newest else None,
            "groups": all_groups, "days": days}


def _parse_iso(value):
    try:
        parsed = datetime.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=KYIV)


# --- Kharkivoblenergo: Drupal JSON:API news posts ---

KHARKIV_URL = "https://oblenergo.kharkiv.ua/uk/jsonapi/node/newsforlenta?" + urllib.parse.urlencode({
    "sort": "-created",
    "page[limit]": "10",
    "fields[node--newsforlenta]": "title,created,changed,body",
    "filter[t][condition][path]": "title",
    "filter[t][condition][operator]": "CONTAINS",
    "filter[t][condition][value]": "погодинн",
})


def parse_kharkiv(payload, now: datetime) -> dict:
    nodes = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(nodes, list):
        raise ValueError("Kharkiv JSON:API: no data list")
    posts = []
    for node in nodes:
        attrs = (node or {}).get("attributes") or {}
        body = (attrs.get("body") or {}).get("value") or ""
        posts.append((attrs.get("title") or "", body, _parse_iso(attrs.get("changed") or attrs.get("created"))))
    return posts_to_entry(posts, now)


# --- Zaporizhzhiaoblenergo: WordPress archive page, newest post first ---

ZAPORIZHZHIA_URL = "https://www.zoe.com.ua/outage/"
_ARTICLE = re.compile(r"<article\b[^>]*>([\s\S]*?)</article>")
_H2 = re.compile(r"<h2[^>]*>([\s\S]*?)</h2>")


def _decode_entities(s: str) -> str:
    for pattern, repl in ((r"&#8211;|&ndash;", "–"), (r"&#8212;|&mdash;", "—"), (r"&nbsp;|&#160;", " "),
                          (r"&#039;|&#8217;|&rsquo;", "’"), (r"&amp;", "&")):
        s = re.sub(pattern, repl, s)
    return s


def parse_zaporizhzhia(page: str, now: datetime) -> dict:
    articles = _ARTICLE.findall(page or "")
    if not articles:
        raise ValueError("Zaporizhzhia: no posts on the page")
    posts = []
    for index, body in enumerate(articles):
        title = _H2.search(body)
        # posts carry no machine timestamps: page order (first = newest) decides
        posts.append((_decode_entities(title.group(1) if title else ""), _decode_entities(body),
                      now - timedelta(seconds=index)))
    entry = posts_to_entry(posts, now)
    entry["updatedAt"] = None  # the page gives no edit times
    return entry


# --- Poltavaoblenergo: HTML fragment with a 48-cell table per group and day ---

POLTAVA_URL = "https://www.poe.pl.ua/customs/dynamicgpv-info.php"
_POLTAVA_CELL = {"s1": None, "s2": OFF, "s3": MAYBE}  # on / off / switching ("може не бути")


def parse_poltava(fragment: str, now: datetime) -> dict:
    blocks = re.split(r'<div class="gpvinfodetail"', fragment or "")[1:]
    if not blocks and re.search(r"gpv-table|turnoff-scheduleui-table", fragment or ""):
        raise ValueError("Poltava: schedule table without day blocks")
    today = kyiv_today(now)
    days, groups = {}, set()
    for block in blocks:
        day = find_ua_date(_TAG.sub(" ", block[:2000]), today)
        if day is None:
            raise ValueError("Poltava: no date in a schedule block")
        table = re.search(r'<table[^>]*class="gpv-table"[^>]*>([\s\S]*?)</table>', block)
        if not table:
            continue
        for row in re.finditer(r"<tr[^>]*>([\s\S]*?)</tr>", table.group(1)):
            name = re.search(r'<td class="gpv-q"[^>]*>([^<]+)</td>', row.group(1))
            group = normalize_group(name.group(1)) if name else None
            if not group:
                continue
            cells = []
            for cell in re.finditer(r'<td class="gpv-c ([^"]*)"', row.group(1)):
                state = re.search(r"\bgpv-(s\d)\b", cell.group(1))
                cells.append(_POLTAVA_CELL.get(state.group(1) if state else "s1"))
            if len(cells) not in (24, 48):
                raise ValueError(f"Poltava: group {group} has {len(cells)} cells")
            days.setdefault(day.isoformat(), {})[group] = _day(cells_to_slots(cells))
            groups.add(group)
    return {"updatedAt": None, "groups": sorted(groups, key=group_key), "days": days}


# --- Chernivtsioblenergo: server-rendered page, 48 half-hour cells per group (groups 1–12) ---

CHERNIVTSI_URL = "https://oblenergo.cv.ua/shutdowns/"
CHERNIVTSI_NEXT_URL = "https://oblenergo.cv.ua/shutdowns/?next"
_CV_CELL = {"u": None, "o": OFF, "s": MAYBE}  # з (on) / в (off) / мз (maybe on)


def parse_chernivtsi_page(page: str):
    """-> (date_iso, {group: day}, updated_at|None)."""
    start = (page or "").find('<div id="gsv"')
    if start == -1:
        raise ValueError("Chernivtsi: no schedule block")
    block = page[start:]
    found = re.search(r"<ul>\s*<p>\s*(\d{2})\.(\d{2})\.(\d{4})", block)
    if not found:
        raise ValueError("Chernivtsi: no date in the schedule block")
    day = f"{found.group(3)}-{found.group(2)}-{found.group(1)}"
    rows = re.findall(r'<div id="inf(\d+)"[^>]*>([\s\S]*?)</div>', block)
    if not rows:
        raise ValueError("Chernivtsi: no group rows")
    groups, any_outage = {}, False
    for group, inner in rows:
        cells = [_CV_CELL[m.group(1)] for m in re.finditer(r"<(u|o|s)>[^<]*</\1>", inner)]
        if len(cells) != 48:
            raise ValueError(f"Chernivtsi: group {group} has {len(cells)} cells")
        any_outage = any_outage or any(c is not None for c in cells)
        groups[str(int(group))] = _day(cells_to_slots(cells))
    if not any_outage:
        for value in groups.values():
            value["s"] = "n"
    return day, groups, _chernivtsi_updated_at(page)


def _chernivtsi_updated_at(page: str):
    """"актуальний станом на <b>23:30</b>" + render stamp "Сторінку відкрито 05.11.2026 о 23:46"."""
    at = re.search(r"актуальний станом на\s*<b>\s*(\d{1,2}):(\d{2})\s*</b>", page)
    opened = re.search(r"Сторінку відкрито\s*(\d{2})\.(\d{2})\.(\d{4})\s*о\s*(\d{1,2}):(\d{2})", page)
    if not at or not opened:
        return None
    minutes = int(at.group(1)) * 60 + int(at.group(2))
    base = datetime(int(opened.group(3)), int(opened.group(2)), int(opened.group(1)), tzinfo=KYIV)
    if minutes > int(opened.group(4)) * 60 + int(opened.group(5)):
        base -= timedelta(days=1)  # a time later than the render time belongs to the day before
    return (base + timedelta(minutes=minutes)).isoformat(timespec="seconds")


def parse_chernivtsi(pages) -> dict:
    days, groups, updated = {}, set(), None
    for page in pages:
        day, day_groups, updated_at = parse_chernivtsi_page(page)
        days[day] = day_groups
        groups.update(day_groups)
        if updated_at and (updated is None or updated_at > updated):
            updated = updated_at
    return {"updatedAt": updated, "groups": sorted(groups, key=group_key), "days": dict(sorted(days.items()))}


# --- fetchers (same contract as UaOutagesFetcher) ---

class _DirectFetcher:
    name = label = website = None
    accept = "text/html"
    as_text = True

    def __init__(self, queue, client=None):
        self.queue = queue
        self.client = client or CachedJsonClient(ttl=600, accept=self.accept, as_text=self.as_text)

    def entry(self, now) -> dict:
        raise NotImplementedError

    def fetch(self, now=None) -> dict:
        now = (now or datetime.now(KYIV)).astimezone(KYIV)
        entry = self.entry(now)
        if entry["groups"] and self.queue not in entry["groups"]:
            raise LookupError("queue_not_found")
        parsed = parse_region_days(entry, self.queue, today=now.date().isoformat())
        return {**parsed, "accidents": [], "stale": False, "detail": self.website}


class KharkivFetcher(_DirectFetcher):
    name, label, website = "kharkiv", "Харківобленерго", "https://oblenergo.kharkiv.ua/uk"
    accept, as_text = "application/vnd.api+json", False

    def entry(self, now):
        return parse_kharkiv(self.client.get(KHARKIV_URL), now)


class PoltavaFetcher(_DirectFetcher):
    name, label, website = "poltava", "Полтаваобленерго", "https://www.poe.pl.ua/disconnection/power-outages/"

    def entry(self, now):
        return parse_poltava(self.client.get(POLTAVA_URL), now)


class ZaporizhzhiaFetcher(_DirectFetcher):
    name, label, website = "zaporizhzhia", "Запоріжжяобленерго", ZAPORIZHZHIA_URL

    def entry(self, now):
        return parse_zaporizhzhia(self.client.get(ZAPORIZHZHIA_URL), now)


class ChernivtsiFetcher(_DirectFetcher):
    name, label, website = "chernivtsi", "Чернівціобленерго", CHERNIVTSI_URL

    def entry(self, now):
        today = self.client.get(CHERNIVTSI_URL)
        pages = [today]
        if 'href="/shutdowns/?next"' in today:  # the link appears once tomorrow is published
            pages.append(self.client.get(CHERNIVTSI_NEXT_URL))
        return parse_chernivtsi(pages)


DIRECT_FETCHERS = {cls.name: cls for cls in (KharkivFetcher, PoltavaFetcher, ZaporizhzhiaFetcher, ChernivtsiFetcher)}

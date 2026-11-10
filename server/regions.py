"""Canonical outage regions (ids = hukpo/ua-outages RegionId) and the sources that cover each.

`official`: direct operator API we read ourselves (loe / yasno), `ua`: present in the ua-outages
aggregate, `svitlo`: slug in the svitlo.live proxies. svitlo slugs are accepted as aliases.
"""
from __future__ import annotations

import re

from svitlo import API_REGION_MAP


def _r(name, operator, svitlo, official=None, ua=True, aliases=()):
    return {"name_ua": name, "operator": operator, "svitlo": svitlo, "official": official, "ua": ua,
            "aliases": tuple(aliases)}


REGIONS = {
    "kyiv": _r("Київ", "ДТЕК Київські електромережі (через YASNO)", "kyiv", "yasno"),
    "kyiv-region": _r("Київська область", "ДТЕК Київські регіональні електромережі", "kiivska-oblast"),
    "dnipro": _r("Дніпропетровська область (ДТЕК)", "ДТЕК Дніпровські електромережі (через YASNO)",
                 "dnipro-dnem", "yasno", aliases=("dnipropetrovska-oblast", "dnipro-city")),
    "dnipro-cek": _r("Дніпропетровська область (ЦЕК)", "Центральна енергетична компанія (через YASNO)",
                     "dnipro-cek", "yasno"),
    "odesa": _r("Одеська область", "ДТЕК Одеські електромережі", "odeska-oblast"),
    "donetsk": _r("Донецька область", "ДТЕК Донецькі електромережі", "donetska-oblast"),
    "mykolaiv": _r("Миколаївська область", "Миколаївобленерго", "mikolaivska-oblast"),
    "poltava": _r("Полтавська область", "Полтаваобленерго", "poltavska-oblast"),
    "kharkiv": _r("Харківська область", "Харківобленерго", "kharkivska-oblast"),
    "zaporizhzhia": _r("Запорізька область", "Запоріжжяобленерго", "zaporizka-oblast"),
    "cherkasy": _r("Черкаська область", "Черкасиобленерго", "cherkaska-oblast"),
    "sumy": _r("Сумська область", "Сумиобленерго", "sumska-oblast"),
    "zhytomyr": _r("Житомирська область", "Житомиробленерго", "zhytomyrska-oblast"),
    "kirovohrad": _r("Кіровоградська область", "Кіровоградобленерго", "kirovogradska-oblast"),
    "lviv": _r("Львівська область", "Львівобленерго", "lvivska-oblast", "loe"),
    "ternopil": _r("Тернопільська область", "Тернопільобленерго", "ternopilska-oblast"),
    "zakarpattia": _r("Закарпатська область", "Закарпаттяобленерго", "zakarpatska-oblast"),
    "ivano-frankivsk": _r("Івано-Франківська область", "Прикарпаттяобленерго", "ivano-frankivska-oblast"),
    "chernivtsi": _r("Чернівецька область", "Чернівціобленерго", "chernivetska-oblast"),
    "rivne": _r("Рівненська область", "Рівнеобленерго", "rivnenska-oblast"),
    "volyn": _r("Волинська область", "Волиньобленерго", "volinska-oblast"),
    "khmelnytskyi": _r("Хмельницька область", "Хмельницькобленерго", "khmelnytska-oblast"),
    "chernihiv": _r("Чернігівська область", "Чернігівобленерго", "chernihivska-oblast"),
    "vinnytsia": _r("Вінницька область", "Вінницяобленерго", "vinnitska-oblast", ua=False),
}
_UZ_RE = re.compile(r"^uz-[a-z-]+$")


def _alias_index():
    index = {}
    for rid, info in REGIONS.items():
        slugs = {info["svitlo"], *info["aliases"]}
        slugs |= {old for old, new in API_REGION_MAP.items() if new in slugs}
        for slug in slugs:
            index.setdefault(slug, rid)
    return index


ALIASES = _alias_index()


def resolve_region(value):
    """Canonical id for a ua-outages id or a svitlo.live slug; None when unknown."""
    value = (value or "").strip().lower()
    if value in REGIONS:
        return value
    if value in ALIASES:
        return ALIASES[value]
    if _UZ_RE.match(value):  # Ukrzaliznytsia grid areas exist only in ua-outages
        return value
    return None


def sources_for(region_id):
    info = REGIONS.get(region_id)
    if info is None:
        return ["ua-outages"] if region_id and _UZ_RE.match(region_id) else []
    return ([info["official"]] if info["official"] else []) + (["ua-outages"] if info["ua"] else []) + \
        (["svitlo"] if info["svitlo"] else [])


def _natural(queue):
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", queue)]


def build_region_catalog(ua_now=None, svitlo_catalog=None) -> list:
    """[{id, name_ua, operator, queues, sources}] — static table enriched with live queue lists."""
    ua_regions = {r.get("id"): r for r in (ua_now or {}).get("regions", []) if isinstance(r, dict)}
    svitlo_queues = {}
    for entry in svitlo_catalog or []:
        rid = resolve_region(entry.get("id"))
        if rid:
            svitlo_queues.setdefault(rid, []).extend(entry.get("queues") or [])
    catalog = []
    ids = list(REGIONS) + [rid for rid in ua_regions if rid not in REGIONS and resolve_region(rid)]
    for rid in ids:
        info = REGIONS.get(rid)
        live = ua_regions.get(rid) or {}
        queues = list(dict.fromkeys(list(live.get("groups") or []) + svitlo_queues.get(rid, [])))
        catalog.append({
            "id": rid,
            "name_ua": (info or {}).get("name_ua") or live.get("name") or rid,
            "operator": live.get("operator") or (info or {}).get("operator"),
            "queues": sorted(queues, key=_natural),
            "sources": sources_for(rid),
        })
    return sorted(catalog, key=lambda r: (r["id"].startswith("uz-"), r["name_ua"]))

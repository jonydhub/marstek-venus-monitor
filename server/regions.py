"""Canonical outage regions (ids = hukpo/ua-outages RegionId) and the sources that cover each.

`official`: direct operator source we read ourselves (loe, yasno, kharkiv, poltava, zaporizhzhia,
chernivtsi — see outage.py / yasno.py / oblenergo.py); `ua`: present in the ua-outages aggregate.
`aliases`: legacy region slugs from earlier configurations (e.g. lvivska-oblast), still accepted.
A region with neither source has no schedule: grid state comes from the backup output only.
"""
from __future__ import annotations

import re

DEFAULT_QUEUES = tuple(f"{n}.{s}" for n in range(1, 7) for s in (1, 2))


def _r(name, operator, aliases=(), official=None, ua=True, queues=DEFAULT_QUEUES):
    return {"name_ua": name, "operator": operator, "official": official, "ua": ua,
            "aliases": tuple(aliases), "queues": tuple(queues)}


REGIONS = {
    "kyiv": _r("Київ", "ДТЕК Київські електромережі (через YASNO)", (), "yasno"),
    "kyiv-region": _r("Київська область", "ДТЕК Київські регіональні електромережі", ("kiivska-oblast",)),
    "dnipro": _r("Дніпропетровська область (ДТЕК)", "ДТЕК Дніпровські електромережі (через YASNO)",
                 ("dnipro-dnem", "dnipropetrovska-oblast", "dnipro-city"), "yasno"),
    "dnipro-cek": _r("Дніпропетровська область (ЦЕК)", "Центральна енергетична компанія (через YASNO)", (), "yasno"),
    "odesa": _r("Одеська область", "ДТЕК Одеські електромережі", ("odeska-oblast",)),
    "donetsk": _r("Донецька область", "ДТЕК Донецькі електромережі", ("donetska-oblast",)),
    "mykolaiv": _r("Миколаївська область", "Миколаївобленерго", ("mikolaivska-oblast",)),
    "poltava": _r("Полтавська область", "Полтаваобленерго", ("poltavska-oblast",), "poltava"),
    "kharkiv": _r("Харківська область", "Харківобленерго", ("kharkivska-oblast", "harkivska-oblast"), "kharkiv"),
    "zaporizhzhia": _r("Запорізька область", "Запоріжжяобленерго", ("zaporizka-oblast",), "zaporizhzhia"),
    "cherkasy": _r("Черкаська область", "Черкасиобленерго", ("cherkaska-oblast",)),
    "sumy": _r("Сумська область", "Сумиобленерго", ("sumska-oblast",)),
    "zhytomyr": _r("Житомирська область", "Житомиробленерго", ("zhytomyrska-oblast", "jitomirska-oblast")),
    "kirovohrad": _r("Кіровоградська область", "Кіровоградобленерго", ("kirovogradska-oblast",)),
    "lviv": _r("Львівська область", "Львівобленерго", ("lvivska-oblast",), "loe"),
    "ternopil": _r("Тернопільська область", "Тернопільобленерго", ("ternopilska-oblast",)),
    "zakarpattia": _r("Закарпатська область", "Закарпаттяобленерго", ("zakarpatska-oblast",)),
    "ivano-frankivsk": _r("Івано-Франківська область", "Прикарпаттяобленерго", ("ivano-frankivska-oblast",)),
    "chernivtsi": _r("Чернівецька область", "Чернівціобленерго", ("chernivetska-oblast",), "chernivtsi",
                     queues=[str(n) for n in range(1, 13)]),
    "rivne": _r("Рівненська область", "Рівнеобленерго", ("rivnenska-oblast",)),
    "volyn": _r("Волинська область", "Волиньобленерго", ("volinska-oblast",)),
    "khmelnytskyi": _r("Хмельницька область", "Хмельницькобленерго", ("khmelnytska-oblast", "hmelnitska-oblast")),
    "chernihiv": _r("Чернігівська область", "Чернігівобленерго", ("chernihivska-oblast", "chernigivska-oblast")),
    # Vinnytsiaoblenergo publishes schedules only as images and a captcha-protected address lookup.
    "vinnytsia": _r("Вінницька область", "Вінницяобленерго", ("vinnitska-oblast",), ua=False, queues=()),
}
_UZ_RE = re.compile(r"^uz-[a-z-]+$")
ALIASES = {slug: rid for rid, info in REGIONS.items() for slug in info["aliases"]}


def resolve_region(value):
    """Canonical id for a ua-outages id or a legacy slug; None when unknown."""
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
    return ([info["official"]] if info["official"] else []) + (["ua-outages"] if info["ua"] else [])


def _natural(queue):
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", queue)]


def build_region_catalog(ua_now=None) -> list:
    """[{id, name_ua, operator, queues, sources}] — static table enriched with live ua-outages queue lists."""
    ua_regions = {r.get("id"): r for r in (ua_now or {}).get("regions", []) if isinstance(r, dict)}
    catalog = []
    ids = list(REGIONS) + [rid for rid in ua_regions if rid not in REGIONS and resolve_region(rid)]
    for rid in ids:
        info = REGIONS.get(rid)
        live = ua_regions.get(rid) or {}
        queues = list(live.get("groups") or []) or list((info or {}).get("queues") or [])
        catalog.append({
            "id": rid,
            "name_ua": (info or {}).get("name_ua") or live.get("name") or rid,
            "operator": live.get("operator") or (info or {}).get("operator"),
            "queues": sorted(dict.fromkeys(queues), key=_natural),
            "sources": sources_for(rid),
        })
    return sorted(catalog, key=lambda r: (r["id"].startswith("uz-"), r["name_ua"]))

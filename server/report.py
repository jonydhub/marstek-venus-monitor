"""«Звіт» — week / month totals from the server's day totals (history.day_totals + today), with the previous period
for comparison and a ready Telegram text. Pure: callers pass the day rows, so tests need no database."""
from __future__ import annotations

from datetime import date, timedelta

TARIFF_UAH = 4.32  # fixed household price with VAT, грн/кВт·год (КМУ, until 30.04.2027) — same as the calculator
KEYS = ("charge_wh", "discharge_wh", "outage_energy_wh", "off_seconds", "outages")
MONTHS = ("січень", "лютий", "березень", "квітень", "травень", "червень", "липень", "серпень", "вересень", "жовтень",
          "листопад", "грудень")
MONTHS_GEN = ("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня",
              "листопада", "грудня")


def period_range(period: str, today: date, offset: int = 0) -> tuple[date, date]:
    """[start, end] (inclusive) of the week (Mon–Sun) / month `offset` periods back; offset 0 ends today."""
    if period == "week":
        start = today - timedelta(days=today.weekday()) - timedelta(weeks=offset)
        end = start + timedelta(days=6)
    elif period == "month":
        y, m = today.year, today.month - offset
        while m < 1:
            y, m = y - 1, m + 12
        start = date(y, m, 1)
        end = (date(y + (m == 12), m % 12 + 1, 1)) - timedelta(days=1)
    else:
        raise ValueError(f"unknown period {period!r}")
    return start, min(end, today)


def summarize(days: list[dict], start: date, end: date, data_since: date | None) -> dict:
    """Totals over [start, end] of `days` (dicts with ISO `date` + KEYS)."""
    picked = [d for d in days if start.isoformat() <= d["date"] <= end.isoformat()]
    total = {k: sum(d.get(k) or 0 for d in picked) for k in KEYS}
    covered_from = max(start, data_since) if data_since else start
    return {
        "start": start.isoformat(), "end": end.isoformat(),
        "days": (end - start).days + 1,
        "covered_days": max(0, (end - covered_from).days + 1) if data_since and data_since <= end else 0,
        "complete": data_since is not None and data_since <= start,
        "days_with_outages": sum(1 for d in picked if (d.get("off_seconds") or 0) >= 60),
        "charge_wh": round(total["charge_wh"], 1), "discharge_wh": round(total["discharge_wh"], 1),
        "outage_energy_wh": round(total["outage_energy_wh"], 1), "off_seconds": round(total["off_seconds"]),
        "outages": int(total["outages"]),
        "charge_cost_uah": round(total["charge_wh"] / 1000 * TARIFF_UAH, 2),
    }


def report(days: list[dict], *, today: date, period: str = "week", offset: int = 0) -> dict:
    """`days`: stored completed days + today (any order). current = the period `offset` back, previous = the one before."""
    known = sorted(d["date"] for d in days)
    data_since = date.fromisoformat(known[0]) if known else None
    cur = summarize(days, *period_range(period, today, offset), data_since)
    prev = summarize(days, *period_range(period, today, offset + 1), data_since)
    body = {"period": period, "offset": offset, "since": data_since.isoformat() if data_since else None,
            "tariff_uah": TARIFF_UAH, "current": cur, "previous": prev}
    body["title"] = title(body)
    body["text"] = telegram_text(body)
    return body


def _span(seconds: float) -> str:
    m = round(seconds / 60)
    h, r = divmod(m, 60)
    return f"{h} год {r} хв" if h and r else f"{h} год" if h else f"{r} хв"


def _kwh(wh: float) -> str:
    return f"{wh / 1000:.2f}".replace(".", ",")


def _uah(v: float) -> str:
    return f"{v:.2f}".replace(".", ",")


def _outages_word(n: int) -> str:
    return "відключення" if n % 10 in (1, 2, 3, 4) and not 11 <= n % 100 <= 14 else "відключень"


def title(body: dict) -> str:
    """«Тиждень 17–23.11», «Листопад 2026» (+ «з 20.11» when the history starts inside the period)."""
    cur = body["current"]
    s, e = date.fromisoformat(cur["start"]), date.fromisoformat(cur["end"])
    if body["period"] == "month":
        text = f"{MONTHS[s.month - 1].capitalize()} {s.year}"
    elif s.month == e.month:
        text = f"Тиждень {s.day}–{e.day:02d}.{e.month:02d}"
    else:
        text = f"Тиждень {s.day:02d}.{s.month:02d}–{e.day:02d}.{e.month:02d}"
    since = body.get("since")
    if since and not cur["complete"] and cur["covered_days"]:
        d = date.fromisoformat(since)
        text += f" (дані з {d.day} {MONTHS_GEN[d.month - 1]})"
    return text


def _delta(cur: float, prev: float, fmt) -> str:
    diff = cur - prev
    if abs(diff) < 1e-9:
        return "стільки ж"
    return ("+" if diff > 0 else "−") + fmt(abs(diff))


def telegram_text(body: dict) -> str:
    cur, prev = body["current"], body["previous"]
    if not cur["covered_days"]:
        return f"⚡ {title(body)}: ще немає вимірів."
    lines = [f"⚡ Marstek · {title(body)}", ""]
    if cur["off_seconds"] >= 60:
        lines.append(f"Без світла: {_span(cur['off_seconds'])} — {cur['outages']} {_outages_word(cur['outages'])}, "
                     f"у {cur['days_with_outages']} з {cur['covered_days']} дн.")
        lines.append(f"Станція віддала за відключення: {_kwh(cur['outage_energy_wh'])} кВт·год")
    else:
        lines.append("Без відключень")
    lines.append(f"Заряджено з мережі: {_kwh(cur['charge_wh'])} кВт·год ≈ {_uah(cur['charge_cost_uah'])} грн")
    lines.append(f"Розряджено: {_kwh(cur['discharge_wh'])} кВт·год")
    if prev["covered_days"]:
        word = "минулим тижнем" if body["period"] == "week" else "минулим місяцем"
        lines += ["", f"Порівняно з {word}"
                  + ("" if prev["complete"] else f" (неповним, {prev['covered_days']} дн.)") + ":",
                  f"• без світла {_delta(cur['off_seconds'], prev['off_seconds'], _span)}",
                  f"• заряджено {_delta(cur['charge_wh'], prev['charge_wh'], _kwh)} кВт·год"]
    return "\n".join(lines)

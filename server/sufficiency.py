"""«Чи вистачить» + charge advice («зарядиться лише до 74 %, на 2,5 кВт — 100 % о 18:40»).

Same rules as Android `Sufficiency` (keep in sync; cases in tests/test_sufficiency.py and SufficiencyTest).
Input is plain numbers so the web, the Telegram alert script and the tests share one implementation."""
from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

KYIV = ZoneInfo("Europe/Kyiv")
MAX_CHARGE_W = 2500.0       # Venus E 3.0 AC charge limit
ADVICE_MARGIN_W = 200.0     # already charging within this of the limit → nothing to raise
NBSP = "\u00a0"


def _num(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if number == number and abs(number) != float("inf") else None


def _span(seconds: float) -> str:
    m = round(max(0.0, seconds) / 60)
    h, r = divmod(m, 60)
    return f"{h}{NBSP}год {r}{NBSP}хв" if h and r else f"{h}{NBSP}год" if h else f"{r}{NBSP}хв"


def _local(ts: float) -> datetime:
    return datetime.fromtimestamp(round(ts / 60) * 60, KYIV)  # nearest minute


def _hm(ts: float) -> str:
    return _local(ts).strftime("%H:%M")


def _clock(ts: float, now: float) -> str:
    """«о 20:00», «завтра о 02:00», «о 09:00, 29.11»."""
    t, today = _local(ts), datetime.fromtimestamp(now, KYIV).date()
    if t.date() == today:
        return f"о {t:%H:%M}"
    if t.date() == today + timedelta(days=1):
        return f"завтра о {t:%H:%M}"
    return f"о {t:%H:%M}, {t:%d.%m}"


def sufficiency(*, now, light, stored_wh, rated_wh=None, floor_pct=None, load_w=None, charge_w=None,
                current_end=None, next_start=None, next_end=None) -> dict:
    """Times are epoch seconds. Returns {"verdict": {"ok", "text"} | None, "advice": str | None}."""
    out: dict = {"verdict": None, "advice": None}
    now, stored = _num(now), _num(stored_wh)
    rated = _num(rated_wh) if (_num(rated_wh) or 0) > 0 else None
    load = _num(load_w) if (_num(load_w) or 0) > 0 else None
    charge: float = _num(charge_w) or 0.0
    charge = charge if charge > 0 else 0.0
    if now is None or stored is None:
        return out
    floor = _num(floor_pct) or 0.0
    floor_wh = rated * floor / 100 if rated is not None and floor > 0 else 0.0
    cur_end, nxt_start, nxt_end = _num(current_end), _num(next_start), _num(next_end)

    def pct(wh: float) -> str:
        return f"{round(wh / (rated or 1) * 100)}{NBSP}%"

    if light == "off" and cur_end is not None and cur_end > now and load is not None:
        runtime, remaining = max(0.0, stored - floor_wh) / load * 3600, cur_end - now
        if runtime >= remaining:
            margin = runtime - remaining
            tail = f" · запас ≈{NBSP}{_span(margin)}" if margin >= 60 else " — впритул"
            out["verdict"] = {"ok": True, "text": f"Вистачить до світла {_clock(cur_end, now)}{tail}"}
        else:
            out["verdict"] = {"ok": False, "text": f"Не вистачить ≈{NBSP}{_span(remaining - runtime)}: заряду до "
                                                   f"{_hm(now + runtime)}, світло {_clock(cur_end, now)}"}
        return out
    if light != "on" or nxt_start is None or nxt_start <= now:
        return out
    t = nxt_start - now
    stored_at = stored + charge * t / 3600
    if rated is not None:
        stored_at = min(rated, stored_at)
    if load is not None and nxt_end is not None and nxt_end > nxt_start:
        rng = f"{_hm(nxt_start)}–{_hm(nxt_end)}"
        need, usable = load * (nxt_end - nxt_start) / 3600, stored_at - floor_wh
        if usable >= need:
            left = f" · лишиться ≈{NBSP}{pct(stored_at - need)}" if rated is not None else ""
            out["verdict"] = {"ok": True, "text": f"На відключення {rng} вистачить{left}"}
        else:
            out["verdict"] = {"ok": False, "text": f"На відключення {rng} не вистачить ≈{NBSP}"
                                                   f"{_span((need - max(0.0, usable)) / load * 3600)}"}
    if charge > 0 and rated is not None and stored_at < rated * 0.99 and charge < MAX_CHARGE_W - ADVICE_MARGIN_W:
        full_at_max = (rated - stored) / MAX_CHARGE_W * 3600
        if full_at_max <= t:
            at_max = f"100{NBSP}% {_clock(now + full_at_max, now)}"
        else:  # not full before the outage → never «100 %»
            at_max = f"до {min(99, round((stored + MAX_CHARGE_W * t / 3600) / rated * 100))}{NBSP}%"
        out["advice"] = (f"До відключення зарядиться лише до {pct(stored_at)}. На 2,5{NBSP}кВт — {at_max}: "
                         f"збільште потужність заряду в застосунку Marstek.")
    return out


def _iso_ts(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def from_status(status: dict, now: float, light: str) -> dict:
    """Inputs from the /api/status payload pieces (power_outage, battery_flow, Bat.GetStatus)."""
    outage = status.get("power_outage") or {}
    autonomy = outage.get("autonomy") or {}
    flow = status.get("battery_flow") or {}
    bat = ((status.get("methods") or {}).get("Bat.GetStatus") or {}).get("data") or {}
    charging = flow.get("available") and flow.get("direction") == "charging"
    return sufficiency(
        now=now, light=light,
        stored_wh=autonomy.get("stored_wh") if autonomy.get("stored_wh") is not None else bat.get("bat_capacity"),
        rated_wh=bat.get("rated_capacity"), floor_pct=autonomy.get("floor_pct"), load_w=autonomy.get("load_w"),
        charge_w=flow.get("power_w") if charging else 0,
        current_end=_iso_ts((outage.get("current_outage") or {}).get("end")),
        next_start=_iso_ts((outage.get("next_outage") or {}).get("start")),
        next_end=_iso_ts((outage.get("next_outage") or {}).get("end")),
    )

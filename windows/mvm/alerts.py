"""Alert engine for Windows toasts — a port of the Android AlertEvaluator (read-only: it only informs).

Differences from Android: «немає даних» fires after 3 consecutive failed polls (never on one dropped packet);
«відключення скоро» fires at 60 and again at 15 minutes before the scheduled start.
"""
from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field
from datetime import datetime

from mvm.snapshot import View, celsius, duration, hhmm, kwh, stamp, view, watts

ALERT_LABELS = {
    "light_lost": "Світло зникло",
    "light_returned": "Світло повернулось",
    "outage_soon": "Відключення скоро (за 60 і 15 хв)",
    "schedule_published": "Графік оновлено",
    "reserve_short_next": "Запасу не вистачить на наступне відключення",
    "reserve_short_current": "Запасу може не вистачити зараз",
    "soc_low": "Заряд нижче 20%",
    "soc_critical": "Заряд нижче 10%",
    "charging_started": "Почалось заряджання",
    "charged_full": "Батарею заряджено",
    "not_charging_after_return": "Світло є, але батарея не заряджається",
    "high_load": "Велике споживання під час відключення",
    "temperature_high": "Висока температура батареї",
    "temperature_cold_charging": "Заряджання на холоді",
    "data_stale": "Немає зв’язку з батареєю",
}
ALERT_IDS = tuple(ALERT_LABELS)

STALE_AFTER_FAILURES = 3
OUTAGE_SOON_LEVELS = (60, 15)
SOC_LOW, SOC_LOW_REARM = 20, 25
SOC_CRITICAL, SOC_CRITICAL_REARM = 10, 15
TEMP_HIGH, TEMP_COLD, TEMP_HYSTERESIS = 45.0, 5.0, 3.0
NOT_CHARGING_SOC, NOT_CHARGING_CHECKS = 90, 2
CHARGING_RENOTIFY_SECONDS = 3600
FULL_REARM_SOC = 95
REAL_CHARGE_FROM_SOC = 97  # «Батарею заряджено» only after a charge that started at ≤ this SOC
DEFAULT_HIGH_LOAD_W = 200
HIGH_LOAD_REARM_FACTOR = 0.8
SEP = " · "


@dataclass(frozen=True)
class Alert:
    id: str
    severity: str  # info / warn / critical
    title: str
    text: str


@dataclass
class Memory:
    grid: str | None = None
    direction: str | None = None
    soc: int | None = None
    had_fresh: bool = False
    failures: int = 0
    stale_sent: bool = False
    outage_started_at: float | None = None
    soc_at_outage_start: int | None = None
    returned_at: float | None = None
    not_charging_checks: int = 0
    not_charging_sent: bool = False
    outage_soon_for: str | None = None
    outage_soon_levels: tuple = ()
    reserve_next_sent_for: str | None = None
    reserve_current_sent: bool = False
    schedule_published: bool | None = None
    last_published_key: str | None = None
    last_next_start: float | None = None
    charging_started_at: float | None = None
    full_sent: bool = False
    low_soc: int | None = None  # lowest SOC since the battery was last at 100 % (standby top-up ≠ a charge)
    high_load_sent: bool = False
    soc_low_sent: bool = False
    soc_critical_sent: bool = False
    temp_high_sent: bool = False
    temp_cold_sent: bool = False


@dataclass
class Evaluation:
    events: list
    active: list
    memory: Memory = field(default_factory=Memory)


def _runtime(v: View):
    if v.runtime_s is not None:
        return v.runtime_s
    return v.discharge_eta_s if v.direction == "discharging" else None


def _load(v: View):
    if v.load_w and v.load_w > 0:
        return v.load_w
    return v.avg_backup_w if v.avg_backup_w and v.avg_backup_w > 0 else None


def _runtime_from_load(capacity, load):
    if capacity is None or not load or load <= 0:
        return None
    return capacity / load * 3600.0


def evaluate(previous: Memory | None, status, now: datetime, enabled: dict, high_load_w=DEFAULT_HIGH_LOAD_W):
    m0 = previous or Memory()
    v = view(status)
    ts = now.timestamp()
    events, active = [], []

    if not v.poll_ok:
        failures = m0.failures + 1
        m = dataclasses.replace(m0, failures=failures)
        if failures >= STALE_AFTER_FAILURES:
            alert = Alert("data_stale", "warn", "Немає зв’язку з батареєю",
                          f"{failures} опитування поспіль без відповіді. Перевірте Wi‑Fi батареї та мережу ПК. "
                          "Інші сповіщення тимчасово не перевіряються.")
            active.append(alert)
            if m0.had_fresh and not m0.stale_sent:
                events.append(alert)
                m = dataclasses.replace(m, stale_sent=True)
        return Evaluation([e for e in events if enabled.get(e.id, True)], active, m)

    m = dataclasses.replace(m0, had_fresh=True, failures=0, stale_sent=False)
    soc, direction = v.soc, v.direction
    last_grid = m0.grid
    grid = v.grid or last_grid
    prev_direction = m0.direction if m0.direction not in (None, "unknown") else None
    runtime = _runtime(v)

    # --- light lost / returned ---
    return_tick = False
    if last_grid == "on" and grid == "off":
        parts = [f"Резервний вихід {watts(v.backup_w)}" if v.backup_w is not None else None,
                 f"заряд {soc}%" if soc is not None else None,
                 f"до 0% ≈ {duration(runtime)} (оцінка)" if runtime else None,
                 f"повернення о {stamp(v.current_end, now)}" if v.current_end else None]
        events.append(Alert("light_lost", "warn", "Світло зникло",
                            SEP.join(p for p in parts if p) or "Батарея живить резервний вихід"))
        m = dataclasses.replace(m, outage_started_at=ts, soc_at_outage_start=soc, returned_at=None,
                                not_charging_checks=0, not_charging_sent=False)
    if last_grid == "off" and grid == "on":
        observed = ts - m0.outage_started_at if m0.outage_started_at else None
        start_soc = m0.soc_at_outage_start
        used = ((start_soc - soc) / 100.0 * v.rated_wh
                if start_soc is not None and soc is not None and start_soc > soc and v.rated_wh else None)
        parts = [f"Без світла ≈ {duration(observed)}" if observed else None,
                 f"витрачено ≈ {kwh(used)} ({start_soc}% → {soc}%)" if used else None,
                 f"заряд {soc}%" if used is None and soc is not None else None,
                 "заряджання почалось" if direction == "charging" else "заряджання ще не почалось"]
        events.append(Alert("light_returned", "info", "Світло повернулось", SEP.join(p for p in parts if p)))
        m = dataclasses.replace(m, outage_started_at=None, soc_at_outage_start=None, returned_at=ts,
                                not_charging_checks=0, not_charging_sent=False)
        return_tick = True
    if grid == "on":
        m = dataclasses.replace(m, reserve_current_sent=False)

    # --- not charging after the light returned ---
    if m.returned_at is not None and not return_tick:
        stuck = grid == "on" and soc is not None and soc < NOT_CHARGING_SOC and direction != "charging"
        if stuck:
            checks = m.not_charging_checks + 1
            m = dataclasses.replace(m, not_charging_checks=checks)
            if checks >= NOT_CHARGING_CHECKS:
                alert = Alert("not_charging_after_return", "warn", "Світло є, але батарея не заряджається",
                              f"Заряд {soc}%, а заряджання так і не почалось. Перевірте режим роботи в застосунку "
                              "Marstek (ця програма лише читає дані).")
                active.append(alert)
                if not m.not_charging_sent:
                    events.append(alert)
                    m = dataclasses.replace(m, not_charging_sent=True)
        else:
            m = dataclasses.replace(m, returned_at=None, not_charging_checks=0, not_charging_sent=False)
    elif return_tick and direction == "charging":
        m = dataclasses.replace(m, returned_at=None)

    # --- before the next scheduled outage ---
    if v.has_outage_info and grid != "off" and v.next_start is not None and v.next_start > now:
        minutes = (v.next_start - now).total_seconds() / 60.0
        next_runtime = v.runtime_s if v.runtime_s is not None else _runtime_from_load(v.capacity_wh, _load(v))
        covers = v.covers
        if covers is None and next_runtime is not None and v.next_duration_s is not None:
            covers = next_runtime >= v.next_duration_s
        level = next((lvl for lvl in sorted(OUTAGE_SOON_LEVELS) if minutes <= lvl), None)
        if level is not None:
            verdict = {True: " — вистачить", False: " — не вистачить"}.get(covers, "")
            alert = Alert("outage_soon", "warn" if covers is False else "info",
                          f"Відключення о {hhmm(v.next_start)}" + (f" (черга {v.group})" if v.group else ""),
                          SEP.join(p for p in [f"Через {duration(minutes * 60)}",
                                               f"заряд {soc}%" if soc is not None else None,
                                               f"запас ≈ {duration(next_runtime)}{verdict}" if next_runtime
                                               else "запас: —"] if p))
            active.append(alert)
            sent = m0.outage_soon_levels if m0.outage_soon_for == v.next_start_raw else ()
            if level not in sent:
                events.append(alert)
                # reaching 15 min also covers 60 min: a late-published start yields one toast
                sent = tuple(sorted(set(sent) | {lvl for lvl in OUTAGE_SOON_LEVELS if lvl >= level}))
            m = dataclasses.replace(m, outage_soon_for=v.next_start_raw, outage_soon_levels=sent)
        if covers is False and v.next_duration_s is not None:
            load = _load(v)
            needed = load * v.next_duration_s / 3600.0 if load else None
            have = v.capacity_wh
            window = (f"Відключення {stamp(v.next_start, now)}" + (f"–{hhmm(v.next_end)}" if v.next_end else "")
                      + f" ({duration(v.next_duration_s)})")
            numbers = None
            if needed is not None and have is not None:
                numbers = f"треба ≈ {kwh(needed)}, є {kwh(have)}" + (
                    f" — бракує ≈ {kwh(needed - have)}" if needed > have else "")
            alert = Alert("reserve_short_next", "warn", "До відключення варто дозарядити",
                          SEP.join(p for p in [f"{window}: {numbers or 'запасу не вистачить'}",
                                               f"запас ≈ {duration(next_runtime)}" if next_runtime else None,
                                               f"оцінка за споживанням {watts(load)}" if load else None] if p))
            active.append(alert)
            if m0.reserve_next_sent_for != v.next_start_raw:
                events.append(alert)
            m = dataclasses.replace(m, reserve_next_sent_for=v.next_start_raw)

    # --- schedule published / changed ---
    if v.has_outage_info and v.schedule_published is not None:
        key = f"{v.next_start_raw}|{v.next_end.isoformat() if v.next_end else None}"
        if v.schedule_published and m0.schedule_published is not None and key != m0.last_published_key:
            progressed = m0.last_next_start is not None and m0.last_next_start <= ts
            if not m0.schedule_published or not progressed:
                text = (f"Наступне: {stamp(v.next_start, now)}" + (f"–{hhmm(v.next_end)}" if v.next_end else "")
                        if v.next_start else "Відключень за графіком не заплановано")
                events.append(Alert("schedule_published", "info",
                                    "Графік оновлено" + (f" (черга {v.group})" if v.group else ""), text))
        m = dataclasses.replace(m, schedule_published=v.schedule_published,
                                last_published_key=key if v.schedule_published else m0.last_published_key,
                                last_next_start=v.next_start.timestamp() if v.next_start else None)

    # --- reserve during the current outage ---
    if grid == "off":
        remaining = (v.current_end - now).total_seconds() if v.current_end else None
        remaining = remaining if remaining and remaining > 0 else None
        covers = v.covers
        if covers is None and runtime is not None and remaining is not None:
            covers = runtime >= remaining
        if covers is False:
            parts = [f"Запас ≈ {duration(runtime)}" if runtime else None,
                     (f"світло повернуть о {stamp(v.current_end, now)}" + (f" (через {duration(remaining)})"
                                                                         if remaining else "")) if v.current_end
                     else None,
                     f"заряд {soc}%" if soc is not None else None, "зменшіть навантаження"]
            alert = Alert("reserve_short_current", "critical", "Запасу може не вистачити",
                          SEP.join(p for p in parts if p))
            active.append(alert)
            if not m.reserve_current_sent:
                events.append(alert)
                m = dataclasses.replace(m, reserve_current_sent=True)

    # --- high load during an outage ---
    if grid == "off" and v.backup_w is not None:
        load = abs(v.backup_w)
        limit = max(float(high_load_w), 2 * v.avg_backup_w if v.avg_backup_w else 0.0)
        if load > limit:
            left = _runtime_from_load(v.capacity_wh, load)
            alert = Alert("high_load", "warn", "Велике споживання під час відключення",
                          f"Споживання {watts(load)}" + (f" — запасу вистачить лише на {duration(left)}" if left else "")
                          + f" (поріг {watts(limit)}; оцінка)")
            active.append(alert)
            if not m.high_load_sent:
                events.append(alert)
                m = dataclasses.replace(m, high_load_sent=True)
        elif load < limit * HIGH_LOAD_REARM_FACTOR:
            m = dataclasses.replace(m, high_load_sent=False)
    elif grid != "off":
        m = dataclasses.replace(m, high_load_sent=False)

    # --- state of charge ---
    if soc is not None:
        if soc >= SOC_LOW_REARM:
            m = dataclasses.replace(m, soc_low_sent=False)
        if soc >= SOC_CRITICAL_REARM:
            m = dataclasses.replace(m, soc_critical_sent=False)
        stored = kwh(v.capacity_wh) if v.capacity_wh is not None else None
        outage_rt = f"до 0% ≈ {duration(runtime)}" if runtime and grid == "off" else None
        body = SEP.join(p for p in [f"Заряд {soc}%", stored, outage_rt] if p)
        if soc < SOC_CRITICAL:
            alert = Alert("soc_critical", "critical", f"Критично низький заряд: {soc}%", body)
            active.append(alert)
            if not m.soc_critical_sent:
                events.append(alert)
                m = dataclasses.replace(m, soc_critical_sent=True, soc_low_sent=True)
        elif soc < SOC_LOW:
            alert = Alert("soc_low", "warn", f"Заряд батареї нижче {SOC_LOW}%", body)
            active.append(alert)
            if not m.soc_low_sent:
                events.append(alert)
                m = dataclasses.replace(m, soc_low_sent=True)

    # --- charging ---
    # Held at 100 % the battery self-drains to 99 % and tops up from the grid every ~40 min: not a charge.
    low = m0.low_soc if m0.low_soc is not None else m0.soc
    if soc is not None:
        low = soc if low is None else min(low, soc)
    real_charge = low is not None and low <= REAL_CHARGE_FROM_SOC
    holding = soc is not None and soc >= 99 and not real_charge
    if (direction == "charging" and prev_direction is not None and prev_direction != "charging" and not holding
            and (m0.charging_started_at is None or ts - m0.charging_started_at > CHARGING_RENOTIFY_SECONDS)):
        estimate = " (оцінка)" if v.power_estimated else ""
        parts = [f"≈ {watts(v.power_w)}{estimate}" if v.power_w else None,
                 f"заряд {soc}%" if soc is not None else None,
                 f"до 100% ≈ {duration(v.charge_eta_s)}" if v.charge_eta_s else None]
        events.append(Alert("charging_started", "info", "Почалось заряджання", SEP.join(p for p in parts if p)))
        m = dataclasses.replace(m, charging_started_at=ts)
    if soc is not None:
        if soc < FULL_REARM_SOC:
            m = dataclasses.replace(m, full_sent=False)
        reached = soc >= 100 and (m0.soc if m0.soc is not None else 100) < 100
        stopped = prev_direction == "charging" and direction == "idle" and soc >= 98
        if (reached or stopped) and real_charge and not m.full_sent:
            events.append(Alert("charged_full", "info", "Батарею заряджено",
                                SEP.join(p for p in [f"Заряд {soc}%",
                                                     kwh(v.capacity_wh) if v.capacity_wh else None] if p)))
            m = dataclasses.replace(m, full_sent=True)
        if soc >= 100:
            low = soc
    m = dataclasses.replace(m, low_soc=low)

    # --- temperature (LiFePO4) ---
    t = v.temperature_c
    if t is not None:
        if t < TEMP_HIGH - TEMP_HYSTERESIS:
            m = dataclasses.replace(m, temp_high_sent=False)
        if t >= TEMP_COLD + TEMP_HYSTERESIS:
            m = dataclasses.replace(m, temp_cold_sent=False)
        if t > TEMP_HIGH:
            alert = Alert("temperature_high", "warn", "Висока температура батареї",
                          f"Температура {celsius(t)} (поріг {TEMP_HIGH:.0f} °C) — забезпечте вентиляцію, "
                          "зменшіть навантаження")
            active.append(alert)
            if not m.temp_high_sent:
                events.append(alert)
                m = dataclasses.replace(m, temp_high_sent=True)
        if t < TEMP_COLD and direction == "charging":
            alert = Alert("temperature_cold_charging", "warn", "Заряджання на холоді",
                          f"Температура {celsius(t)} під час заряджання — LiFePO4 не варто заряджати нижче "
                          f"{TEMP_COLD:.0f} °C")
            active.append(alert)
            if not m.temp_cold_sent:
                events.append(alert)
                m = dataclasses.replace(m, temp_cold_sent=True)

    m = dataclasses.replace(m, grid=grid, direction=direction if direction != "unknown" else m0.direction,
                            soc=soc if soc is not None else m0.soc)
    order = {"critical": 2, "warn": 1, "info": 0}
    return Evaluation([e for e in events if enabled.get(e.id, True)],
                      sorted(active, key=lambda a: -order[a.severity]), m)


class AlertMonitor:
    """Feeds each *new* poll (by collected_at) into evaluate(); remembers state between polls."""

    def __init__(self, enabled_provider, high_load_w=DEFAULT_HIGH_LOAD_W):
        self.enabled_provider = enabled_provider
        self.high_load_w = high_load_w
        self.memory = Memory()
        self.active = []
        self._last_collected = None

    @property
    def consecutive_failures(self) -> int:
        return self.memory.failures

    @property
    def is_stale(self) -> bool:
        return self.memory.failures >= STALE_AFTER_FAILURES

    def reset(self):
        self.memory, self.active, self._last_collected = Memory(), [], None

    def observe(self, status, now: datetime):
        collected = (status or {}).get("collected_at")
        if not collected or collected == self._last_collected:
            return []
        self._last_collected = collected
        result = evaluate(self.memory, status, now, self.enabled_provider() or {}, self.high_load_w)
        self.memory, self.active = result.memory, result.active
        return result.events

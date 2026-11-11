"""Event journal detector: turns consecutive polls into timestamped events (pure, no I/O).

Light on/off comes ONLY from the battery's backup output (grid_state from detect_grid_state).
A lost connection is its own event and never implies «світла нема»: while the device does not answer,
the grid state is unknown and the last known state is kept, so no light event fires.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace

CONNECTION_LOST_AFTER = 3          # consecutive failed polls
DEFAULT_ANOMALY_DISCHARGE_W = 2000.0
ANOMALY_DISCHARGE_MIN_W, ANOMALY_DISCHARGE_MAX_W = 200.0, 10000.0
DISCHARGE_ON_GRID_W = 100.0        # battery discharging this much while the backup output idles on grid
TEMP_HIGH_C, TEMP_REARM_C = 45.0, 42.0
FULL_SOC = 99
REARM_FACTOR = 0.8
REAL_CHARGE_FROM_SOC = 97          # «Батарею заряджено» only after a charge that started at ≤ this SOC
TOPUP_REPEAT_SECONDS = 6 * 3600    # standby top-ups (100→99→100 %) are journaled at most this often
ON_GRID_MIN_SAMPLES, ON_GRID_MIN_SECONDS = 3, 120

CATEGORIES = {
    "light_lost": "light", "light_returned": "light",
    "charging_started": "battery", "charging_stopped": "battery", "charged_full": "battery",
    "maintenance_topup": "battery",
    "connection_lost": "connection", "connection_restored": "connection",
    "anomaly_high_discharge": "anomaly", "anomaly_discharge_on_grid": "anomaly", "anomaly_temperature": "anomaly",
    "server_started": "system", "initial_state": "system",
}


@dataclass(frozen=True)
class Observation:
    """One poll. `ok` = the battery answered Bat/ES status; the rest is ignored when not ok."""
    ok: bool
    grid_state: str = "unknown"      # on / off / unknown — from backup output only
    direction: str = "unknown"       # charging / discharging / idle / unknown (capacity trend)
    battery_w: float | None = None   # signed battery power (− = discharging)
    battery_measured: bool = False   # True = from ES ongrid/offgrid power; False = capacity-trend estimate
    backup_w: float | None = None    # ES.GetStatus offgrid_power
    soc: int | None = None
    temp_c: float | None = None


@dataclass(frozen=True)
class DetectorState:
    grid: str | None = None
    direction: str | None = None
    soc: int | None = None
    failures: int = 0
    connection_lost: bool = False
    lost_at: float | None = None
    outage_started_at: float | None = None
    full_sent: bool = False
    high_discharge_sent: bool = False
    on_grid_sent: bool = False
    temp_sent: bool = False
    low_soc: int | None = None          # lowest SOC since the battery was last ≥ FULL_SOC
    topup_at: float | None = None       # last «Підзаряд до 100% (утримання)» record
    on_grid_count: int = 0              # consecutive measured discharge samples with grid present
    on_grid_since: float | None = None


def _num(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def duration_text(seconds) -> str:
    minutes = max(0, int(round(float(seconds) / 60)))
    hours, minutes = divmod(minutes, 60)
    return f"{hours} год {minutes} хв" if hours else f"{minutes} хв"


def _event(ts, kind, text, value=None):
    return {"ts": float(ts), "kind": kind, "category": CATEGORIES[kind], "text": text,
            "value": None if value is None else round(float(value), 1)}


def server_started_event(now, version=None):
    """One «Сервер запущено» line per process start, so the journal is never empty after a (re)start."""
    suffix = f" (версія {version})" if version else ""
    return _event(now, "server_started", f"Сервер запущено{suffix}; журнал фіксує зміни світла, заряду та зв'язку")


def initial_state_event(obs: Observation, now):
    """The state at the first answered poll after start: light (backup output only), battery direction, SOC."""
    grid = {"on": "світло є", "off": "світла нема"}.get(obs.grid_state, "стан світла невідомий")
    backup = _num(obs.backup_w)
    if obs.grid_state == "off" and backup is not None:
        grid += f" (резервний вихід {round(abs(backup))} Вт)"
    direction = {"charging": "заряджається", "discharging": "розряджається", "idle": "очікування"}.get(obs.direction)
    parts = [grid, direction, f"заряд {obs.soc}%" if obs.soc is not None else None]
    return _event(now, "initial_state", "Стан на старті: " + " · ".join(p for p in parts if p),
                  obs.soc if obs.soc is not None else None)


def clamp_threshold(value) -> float:
    number = _num(value)
    if number is None:
        return DEFAULT_ANOMALY_DISCHARGE_W
    return min(ANOMALY_DISCHARGE_MAX_W, max(ANOMALY_DISCHARGE_MIN_W, number))


def step(state: DetectorState | None, obs: Observation, now: float,
         discharge_threshold_w=DEFAULT_ANOMALY_DISCHARGE_W):
    """Return (events, new_state). Events are dicts {ts, kind, category, text, value}."""
    s = state or DetectorState()
    events = []
    if not obs.ok:
        failures = s.failures + 1
        s = replace(s, failures=failures)
        if failures >= CONNECTION_LOST_AFTER and not s.connection_lost:
            events.append(_event(now, "connection_lost",
                                 f"Немає зв'язку з батареєю: {failures} опитування поспіль без відповіді "
                                 "(стан світла невідомий)"))
            s = replace(s, connection_lost=True, lost_at=now)
        return events, s

    if s.connection_lost:
        gap = f" після {duration_text(now - s.lost_at)}" if s.lost_at else ""
        events.append(_event(now, "connection_restored", f"Зв'язок з батареєю відновлено{gap}"))
    s = replace(s, failures=0, connection_lost=False, lost_at=None)

    soc = obs.soc
    backup = _num(obs.backup_w)
    battery = _num(obs.battery_w)
    grid = obs.grid_state if obs.grid_state in ("on", "off") else None
    soc_text = f", заряд {soc}%" if soc is not None else ""

    # --- light: backup-output power only; unknown keeps the last known state ---
    if grid is not None and s.grid is not None and grid != s.grid:
        if grid == "off":
            watts = f"резервний вихід {round(abs(backup))} Вт" if backup is not None else "живлення від батареї"
            events.append(_event(now, "light_lost", f"Світло зникло: {watts}{soc_text}", backup))
            s = replace(s, outage_started_at=now)
        else:
            lasted = f" після {duration_text(now - s.outage_started_at)}" if s.outage_started_at else ""
            events.append(_event(now, "light_returned", f"Світло повернулось{lasted}{soc_text}"))
            s = replace(s, outage_started_at=None)
    if grid is not None:
        s = replace(s, grid=grid)

    # --- charging ---
    # A Venus E at 100 % self-drains to 99 % and tops itself up from the grid (~1 min, ≈ 1.2 kW) about every
    # 40 min. That is maintenance, not a charge: no «Почалось заряджання», no «Батарею заряджено», at most one
    # low-key «Підзаряд до 100% (утримання)» per TOPUP_REPEAT_SECONDS.
    direction = obs.direction if obs.direction in ("charging", "discharging", "idle") else None
    prev_dir = s.direction
    low = s.low_soc if s.low_soc is not None else s.soc
    if soc is not None:
        low = soc if low is None else min(low, soc)
    holding = soc is not None and soc >= FULL_SOC and (low is None or low > REAL_CHARGE_FROM_SOC) and s.grid != "off"
    if direction is not None and prev_dir is not None and direction != prev_dir:
        if direction == "charging" and not holding:
            power = ""
            if battery is not None:
                power = f" ≈ {round(abs(battery))} Вт" + ("" if obs.battery_measured else " (оцінка)")
            events.append(_event(now, "charging_started", f"Почалось заряджання{power}{soc_text}", battery))
        elif prev_dir == "charging" and not (soc is not None and soc >= FULL_SOC):
            events.append(_event(now, "charging_stopped", f"Заряджання зупинилось{soc_text}"))
    if soc is not None:
        if soc < 95:
            s = replace(s, full_sent=False)
        rose = s.soc is not None and soc > s.soc
        if soc >= FULL_SOC and (rose or prev_dir == "charging"):
            if low is not None and low <= REAL_CHARGE_FROM_SOC:
                if not s.full_sent:
                    events.append(_event(now, "charged_full", f"Батарею заряджено: {soc}%", soc))
                    s = replace(s, full_sent=True)
            elif rose and s.grid != "off" and (s.topup_at is None or now - s.topup_at >= TOPUP_REPEAT_SECONDS):
                events.append(_event(now, "maintenance_topup", f"Підзаряд до {soc}% (утримання)", soc))
                s = replace(s, topup_at=now)
        if soc >= FULL_SOC:
            low = soc
    s = replace(s, low_soc=low)
    if direction is not None:
        s = replace(s, direction=direction)

    # --- anomalies: from measured power only (backup output during an outage, ongrid/offgrid otherwise) ---
    threshold = clamp_threshold(discharge_threshold_w)
    measured_discharge = battery is not None and obs.battery_measured and direction == "discharging"
    discharge = max(abs(backup) if backup is not None and s.grid == "off" else 0.0,
                    abs(battery) if measured_discharge else 0.0)
    if discharge > threshold:
        if not s.high_discharge_sent:
            events.append(_event(now, "anomaly_high_discharge",
                                 f"Розрядка {round(discharge)} Вт перевищує поріг {round(threshold)} Вт{soc_text}",
                                 discharge))
            s = replace(s, high_discharge_sent=True)
    elif discharge < threshold * REARM_FACTOR:
        s = replace(s, high_discharge_sent=False)

    on_grid = (grid == "on" and measured_discharge and abs(battery) > DISCHARGE_ON_GRID_W
               and (backup is None or abs(backup) < 5.0))
    if on_grid:
        since = s.on_grid_since if s.on_grid_since is not None else now
        s = replace(s, on_grid_count=s.on_grid_count + 1, on_grid_since=since)
        if (not s.on_grid_sent and s.on_grid_count >= ON_GRID_MIN_SAMPLES
                and now - since >= ON_GRID_MIN_SECONDS):
            events.append(_event(now, "anomaly_discharge_on_grid",
                                 f"Розряд ≈ {round(abs(battery))} Вт (виміряно) при наявності мережі вже "
                                 f"{duration_text(now - since)}, резервний вихід не живить навантаження — "
                                 "перевірте режим/розклад розряду або ДБЖ/інвертор", abs(battery)))
            s = replace(s, on_grid_sent=True)
    else:
        s = replace(s, on_grid_count=0, on_grid_since=None)
        if direction != "discharging":
            s = replace(s, on_grid_sent=False)

    temp = _num(obs.temp_c)
    if temp is not None:
        if temp > TEMP_HIGH_C and not s.temp_sent:
            events.append(_event(now, "anomaly_temperature",
                                 f"Висока температура батареї: {temp:.1f} °C (поріг {TEMP_HIGH_C:.0f} °C)", temp))
            s = replace(s, temp_sent=True)
        elif temp < TEMP_REARM_C:
            s = replace(s, temp_sent=False)

    if soc is not None:
        s = replace(s, soc=soc)
    return events, s

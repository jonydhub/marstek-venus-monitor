"""Confirmed grid (light) state from the backup output, with hysteresis — pure, no I/O.

Why: a Venus E in UPS mode feeds its backup output only while the grid is absent, but a load on that output
(an inverter air conditioner, a fridge) regularly drops to 0 W on its own — the compressor pauses. «Backup output
0 W» therefore does NOT prove the grid is back. Rules (shared with the Android direct mode, keep in sync):

* light lost  — immediately, as soon as the backup output draws ≥ threshold W (but see «bypass» below);
* light back  — only when (a) ``ongrid_power`` < −50 W (the battery charges from the grid — impossible without it)
  or (b) the backup output stayed below the threshold for ≥ 5 consecutive minutes with no failed poll in between;
* otherwise the state stays OFF («Світла нема · навантаження зараз 0 Вт»).

Bypass (UPS pass-through): some firmwares/units report the backup-port load in ``offgrid_power`` even while the grid
is present and feeds that load through the station. Our fw148 Venus E reads 0 W there with the grid, so for it the
plain rule above holds; for the others it would show «Світла нема» all day. Hence:

* A — grid import beats backup draw: ``ongrid_power`` < −50 W means the grid is there, even if the backup output
  draws (``confirmed_by="grid_charging"``);
* B — the battery does not discharge while the backup output draws: during a draw the expected energy
  E = ∫offgrid dt (Wh) is compared with the observed drop D of ``bat_wh`` (Bat.GetStatus bat_capacity, ~5 Wh steps).
  E ≥ 30 Wh with D < 0.25·E → the load comes from the grid (``confirmed_by="bypass_no_discharge"``, back-dated to
  the start of the window). A real outage keeps OFF: D tracks E (it even exceeds it — conversion losses);
* either A or B learns ``bypass_load_seen`` (persisted per device by the caller). Once learned — or forced by
  ``bypass="yes"`` (MARSTEK_BYPASS_LOAD) — a new backup draw no longer means «no light» by itself: it is a
  *pending loss* (previous state kept) until the battery visibly discharges (D ≥ 10 Wh and D ≥ 0.4·E), then OFF,
  back-dated to the start of the window. ``ongrid`` ≈ 0 alone is NOT proof of an outage (a full battery in bypass
  does not import anything for itself);
* the window restarts after each decision, after ~120 Wh without one, and whenever the draw stops, a poll fails, the
  pause between answers exceeds ``confirm_gap`` or ``bat_wh`` is missing — so a long bypass draw cannot hide a later
  outage. ``bypass="no"`` is the legacy behaviour (immediate loss, nothing learned). ``bat_power`` is not used: some
  firmwares omit or misreport it.

Overload trip: the station's backup output trips on an instantaneous surge (Venus E: 2.5 kVA rated, 3.5 kVA for 10 s,
error 405). Pattern: the last backup reading was ≥ 500 W (or rising fast), then ≥ 1 poll without an answer, then the
device answers with 0 W. That is reported once and blocks rule (b): after a trip only grid charging proves the light.

A failed poll (no ES answer: ``offgrid`` is None) breaks a zero-load run; the confirmed state is kept.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace

RETURN_CONFIRM_SECONDS = 300.0
GRID_CHARGE_W = -50.0              # ongrid_power below this = charging from the grid = the grid is present
TRIP_MIN_W = 500.0                 # last backup reading at/above this before a silent poll → suspected trip
TRIP_RISE_MIN_W, TRIP_RISE_STEP_W = 300.0, 150.0   # … or rising fast: ≥ 300 W and +150 W since the previous reading
DEFAULT_THRESHOLD_W = 5.0
DEFAULT_CONFIRM_GAP_SECONDS = 120.0  # a longer silence between two answers breaks a zero-load run
# Bypass detection (rule B / learned mode). bat_wh moves in ~5 Wh steps, so a decision needs several steps of signal:
BYPASS_WINDOW_WH = 30.0            # expected energy E (∫ backup W dt) needed before judging «no discharge»
BYPASS_MAX_DROP_RATIO = 0.25       # D < 0.25·E → the battery did not pay for the load → grid present (bypass)
DISCHARGE_MIN_WH = 10.0            # learned mode: an outage needs D ≥ 10 Wh (two capacity steps) …
DISCHARGE_MIN_RATIO = 0.4          # … and D ≥ 0.4·E
BYPASS_WINDOW_MAX_WH = 120.0       # an inconclusive window (0.25–0.4) restarts after this much expected energy
BYPASS_MODES = ("auto", "yes", "no")


def _num(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def confirm_gap_seconds(poll_seconds) -> float:
    """Longest pause between two polls that still counts as «consecutive» (≥ 120 s, 2.5 × the poll interval)."""
    poll = _num(poll_seconds) or 0.0
    return max(DEFAULT_CONFIRM_GAP_SECONDS, 2.5 * poll)


@dataclass(frozen=True)
class GridTracker:
    state: str | None = None            # confirmed: "on" / "off" / None (not known yet)
    off_since: float | None = None      # start of the current outage (backup output first drew power)
    on_since: float | None = None       # when the light was confirmed back (or first seen on)
    zero_since: float | None = None     # first sample of the current zero-load run while OFF
    last_ts: float | None = None        # last poll (answered or not)
    last_backup: float | None = None    # last answered backup-output reading (W)
    prev_backup: float | None = None
    failed_since_backup: bool = False   # a poll without an answer after last_backup
    tripped: bool = False               # suspected overload trip in this outage: only grid charging confirms the light
    trip_w: float | None = None
    trip_at: float | None = None
    confirmed_by: str | None = None     # "grid_charging" / "idle_5min" / "bypass_no_discharge" for the last return
    lost_by: str | None = None          # "backup_draw" (immediate) / "battery_discharge" (learned bypass mode)
    bypass_load_seen: bool = False      # learned: this unit reports backup load while the grid is present
    bypass_active: bool = False         # the current backup draw was attributed to the grid (rule A or B)
    draw_since: float | None = None     # start of the current energy window (backup drawing, bat_wh known)
    draw_start_wh: float | None = None  # bat_wh at draw_since
    draw_wh: float = 0.0                # expected energy E of the window (Wh)


@dataclass(frozen=True)
class GridSignal:
    lost: bool = False                  # OFF confirmed at this poll (was ON or unknown)
    returned_at: float | None = None    # ON confirmed at this poll; time the light is considered back
    trip_w: float | None = None         # suspected overload trip detected at this poll (last backup W)
    pending: bool = False               # OFF, but the backup output reads < threshold now (load paused?)
    lost_at: float | None = None        # with ``lost``: when the light is considered gone (back-dated in bypass mode)
    pending_loss: bool = False          # learned bypass mode: the backup draws, the battery has not discharged yet


def normalize_bypass_mode(value) -> str:
    """MARSTEK_BYPASS_LOAD / app setting → auto / yes / no (unknown → auto)."""
    text = str(value or "").strip().lower()
    aliases = {"1": "yes", "true": "yes", "on": "yes", "0": "no", "false": "no", "off": "no"}
    text = aliases.get(text, text)
    return text if text in BYPASS_MODES else "auto"


def step(t: GridTracker | None, ts: float, offgrid_w, ongrid_w, bat_wh=None, *, threshold_w=DEFAULT_THRESHOLD_W,
         confirm_gap=DEFAULT_CONFIRM_GAP_SECONDS, bypass="auto") -> tuple[GridTracker, GridSignal]:
    """Feed one poll. ``offgrid_w`` None = the battery did not answer ES.GetStatus (failed poll).

    ``bat_wh``: stored energy (Bat.GetStatus bat_capacity) for the bypass check; ``bypass``: auto / yes / no.
    """
    t = t or GridTracker()
    ts = float(ts)
    offgrid, ongrid, wh = _num(offgrid_w), _num(ongrid_w), _num(bat_wh)
    threshold = float(threshold_w)
    mode = normalize_bypass_mode(bypass)
    if offgrid is None:
        return (replace(t, zero_since=None, failed_since_backup=True, last_ts=ts, **_NO_WINDOW),
                GridSignal(pending=t.state == "off"))
    if t.last_ts is not None and ts - t.last_ts > confirm_gap:
        t = replace(t, zero_since=None, **_NO_WINDOW)
    signal = GridSignal()
    grid_import = ongrid is not None and ongrid < GRID_CHARGE_W
    if offgrid >= threshold and mode != "no":
        t, signal = _draw(t, ts, offgrid, wh, grid_import, mode)
    elif offgrid >= threshold:
        lost = t.state != "off"
        t = replace(t, state="off", off_since=t.off_since if t.state == "off" and t.off_since else ts,
                    on_since=None, zero_since=None, tripped=False, trip_w=None, trip_at=None, confirmed_by=None,
                    lost_by="backup_draw" if lost else t.lost_by)
        signal = GridSignal(lost=lost, lost_at=ts if lost else None)
    elif grid_import:
        t = replace(t, **_NO_WINDOW)
        was_off = t.state == "off"
        t = replace(t, state="on", off_since=None, on_since=ts if t.state != "on" else (t.on_since or ts),
                    zero_since=None, tripped=False, confirmed_by="grid_charging" if was_off else t.confirmed_by)
        signal = GridSignal(returned_at=ts if was_off else None)
    elif t.state == "on":
        t = replace(t, zero_since=None, **_NO_WINDOW)
    else:
        t = replace(t, **_NO_WINDOW)
        trip_now = None
        last, prev = t.last_backup, t.prev_backup
        if t.state == "off" and t.failed_since_backup and not t.tripped and last is not None and (
                last >= TRIP_MIN_W or (last >= TRIP_RISE_MIN_W and prev is not None and last - prev >= TRIP_RISE_STEP_W)):
            trip_now = last
            t = replace(t, tripped=True, trip_w=last, trip_at=ts)
        zero_since = t.zero_since if t.zero_since is not None else ts
        if not t.tripped and ts - zero_since >= RETURN_CONFIRM_SECONDS:
            was_off = t.state == "off"
            back = zero_since if was_off else ts
            t = replace(t, state="on", off_since=None, on_since=back, zero_since=None,
                        confirmed_by="idle_5min" if was_off else None)
            signal = GridSignal(returned_at=back if was_off else None, trip_w=trip_now)
        else:
            t = replace(t, zero_since=zero_since)
            signal = GridSignal(trip_w=trip_now, pending=t.state == "off")
    t = replace(t, last_ts=ts, prev_backup=t.last_backup, last_backup=offgrid, failed_since_backup=False)
    return t, signal


_NO_WINDOW = {"draw_since": None, "draw_start_wh": None, "draw_wh": 0.0, "bypass_active": False}


def _draw(t: GridTracker, ts: float, offgrid: float, wh, grid_import: bool, mode: str):
    """The backup output draws (≥ threshold) and bypass handling is on (auto / yes): rules A and B, learned mode."""
    # energy window: E = ∫ backup W dt since draw_since, D = bat_wh drop since then (needs bat_wh at every poll)
    if wh is None:
        t = replace(t, draw_since=None, draw_start_wh=None, draw_wh=0.0)
    elif t.draw_since is None or t.draw_start_wh is None or t.last_backup is None or t.last_ts is None:
        t = replace(t, draw_since=ts, draw_start_wh=wh, draw_wh=0.0)
    else:
        t = replace(t, draw_wh=t.draw_wh + (t.last_backup + offgrid) / 2.0 * max(0.0, ts - t.last_ts) / 3600.0)
    window = t.draw_since is not None and t.draw_start_wh is not None
    expected = t.draw_wh if window else 0.0
    drop = (t.draw_start_wh - wh) if window else 0.0
    restart = {"draw_since": ts, "draw_start_wh": wh, "draw_wh": 0.0} if wh is not None else {}
    learned = t.bypass_load_seen or mode == "yes"
    since = t.draw_since if window else ts
    if grid_import or (window and expected >= BYPASS_WINDOW_WH and drop < BYPASS_MAX_DROP_RATIO * expected):
        # A / B: the grid feeds the backup load (bypass) → light ON, learn it
        basis = "grid_charging" if grid_import else "bypass_no_discharge"
        was_off = t.state == "off"
        back = ts if grid_import else since
        t = replace(t, state="on", off_since=None, on_since=t.on_since if t.state == "on" and t.on_since else back,
                    zero_since=None, tripped=False, trip_w=None, trip_at=None,
                    confirmed_by=basis if t.state != "on" else t.confirmed_by,
                    bypass_load_seen=mode == "auto" or t.bypass_load_seen, bypass_active=True, **restart)
        return t, GridSignal(returned_at=back if was_off else None)
    if window and drop >= DISCHARGE_MIN_WH and drop >= DISCHARGE_MIN_RATIO * expected:
        # the battery pays for the load → a real outage (in learned mode: confirmed only now, back-dated)
        lost = t.state != "off"
        at = since if learned else ts
        t = replace(t, state="off", off_since=t.off_since if not lost and t.off_since else at, on_since=None,
                    zero_since=None, tripped=False, trip_w=None, trip_at=None, confirmed_by=None,
                    lost_by=("battery_discharge" if learned else "backup_draw") if lost else t.lost_by,
                    bypass_active=False, **restart)
        return t, GridSignal(lost=lost, lost_at=at if lost else None)
    if window and expected >= BYPASS_WINDOW_MAX_WH:
        t = replace(t, **restart)  # inconclusive (partly from the battery?): judge a fresh window
    if learned and t.state != "off" and not t.bypass_active:
        # learned bypass: a new draw is not an outage until the battery visibly discharges — keep the state
        return replace(t, zero_since=None), GridSignal(pending_loss=True)
    if learned and t.bypass_active:
        return replace(t, zero_since=None), GridSignal()  # still the same grid-fed draw, re-judged per window
    # legacy rule: the backup output draws → no light, immediately
    lost = t.state != "off"
    t = replace(t, state="off", off_since=t.off_since if not lost and t.off_since else ts, on_since=None,
                zero_since=None, tripped=False, trip_w=None, trip_at=None, confirmed_by=None,
                lost_by="backup_draw" if lost else t.lost_by, bypass_active=False)
    return t, GridSignal(lost=lost, lost_at=ts if lost else None)


def _seed(tracker, bypass_seen):
    tracker = tracker or GridTracker()
    return replace(tracker, bypass_load_seen=True) if bypass_seen and not tracker.bypass_load_seen else tracker


def replay(rows, *, threshold_w=DEFAULT_THRESHOLD_W, confirm_gap=DEFAULT_CONFIRM_GAP_SECONDS, tracker=None,
           bypass="auto", bypass_seen=False):
    """Run :func:`step` over history rows ({ts, offgrid_w, ongrid_w, bat_wh}, any order) → final tracker.

    ``bypass_seen``: the persisted learned flag of this device (ignored with ``bypass="no"``)."""
    mode = normalize_bypass_mode(bypass)
    tracker = _seed(tracker, bypass_seen and mode != "no")
    for row in sorted((r for r in rows if r.get("ts") is not None), key=lambda r: r["ts"]):
        tracker, _ = step(tracker, row["ts"], row.get("offgrid_w"), row.get("ongrid_w"), row.get("bat_wh"),
                          threshold_w=threshold_w, confirm_gap=confirm_gap, bypass=mode)
    return tracker


def timeline(rows, *, start, now, threshold_w=DEFAULT_THRESHOLD_W, confirm_gap=DEFAULT_CONFIRM_GAP_SECONDS,
             max_gap=300.0, bypass="auto", bypass_seen=False):
    """Merged [{start, end, state}] (epoch seconds) over [start, now]: state on / off / unknown.

    Each span between two consecutive polls ≤ ``max_gap`` apart takes the confirmed state after the earlier poll
    (a single failed poll keeps the last confirmed state); a light return confirmed by 5 min without load (or by
    «no discharge» in bypass) is back-dated to the start of that run, a loss confirmed by battery discharge in the
    learned bypass mode to the start of its window. Longer gaps, time before the first poll and an unconfirmed
    state are ``unknown``. The last poll extends to ``now`` only when it is ≤ ``max_gap`` old.
    """
    mode = normalize_bypass_mode(bypass)
    points = sorted((r for r in rows if r.get("ts") is not None and r["ts"] <= now), key=lambda r: r["ts"])
    states, tracker = [], _seed(None, bypass_seen and mode != "no")
    for row in points:
        tracker, signal = step(tracker, row["ts"], row.get("offgrid_w"), row.get("ongrid_w"), row.get("bat_wh"),
                               threshold_w=threshold_w, confirm_gap=confirm_gap, bypass=mode)
        back_to, back_state = ((signal.returned_at, "on") if signal.returned_at is not None
                               else (signal.lost_at, "off") if signal.lost and signal.lost_at is not None
                               else (None, None))
        if back_to is not None:
            for i in range(len(states) - 1, -1, -1):  # back-date to the start of the confirming run / window
                if states[i][0] < back_to:
                    break
                states[i] = (states[i][0], back_state)
        states.append((row["ts"], tracker.state or "unknown"))
    spans = []
    for i, (t0, state) in enumerate(states):
        t1 = states[i + 1][0] if i + 1 < len(states) else now
        if t1 - t0 > max_gap:
            spans.append((t0, t0, state))  # zero-length: the gap is unknown
            continue
        spans.append((t0, t1, state))
    merged = []
    cursor = start
    for t0, t1, state in spans:
        a, b = max(t0, start), min(t1, now)
        if b <= a:
            continue
        if a > cursor:
            _push(merged, cursor, a, "unknown")
        _push(merged, a, b, state)
        cursor = b
    if now > cursor:
        _push(merged, cursor, now, "unknown")
    return [{"start": a, "end": b, "state": s} for a, b, s in merged]


def _push(merged, a, b, state):
    if b <= a:
        return
    if merged and merged[-1][2] == state and abs(merged[-1][1] - a) < 1e-6:
        merged[-1][1] = b
    else:
        merged.append([a, b, state])

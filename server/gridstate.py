"""Confirmed grid (light) state from the backup output, with hysteresis — pure, no I/O.

Why: a Venus E in UPS mode feeds its backup output only while the grid is absent, but a load on that output
(an inverter air conditioner, a fridge) regularly drops to 0 W on its own — the compressor pauses. «Backup output
0 W» therefore does NOT prove the grid is back. Rules (shared with the Android direct mode, keep in sync):

* light lost  — immediately, as soon as the backup output draws ≥ threshold W;
* light back  — only when (a) ``ongrid_power`` < −50 W (the battery charges from the grid — impossible without it)
  or (b) the backup output stayed below the threshold for ≥ 5 consecutive minutes with no failed poll in between;
* otherwise the state stays OFF («Світла нема · навантаження зараз 0 Вт»).

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
    confirmed_by: str | None = None     # "grid_charging" / "idle_5min" for the last return


@dataclass(frozen=True)
class GridSignal:
    lost: bool = False                  # OFF confirmed at this poll (was ON or unknown)
    returned_at: float | None = None    # ON confirmed at this poll; time the light is considered back
    trip_w: float | None = None         # suspected overload trip detected at this poll (last backup W)
    pending: bool = False               # OFF, but the backup output reads < threshold now (load paused?)


def step(t: GridTracker | None, ts: float, offgrid_w, ongrid_w, *, threshold_w=DEFAULT_THRESHOLD_W,
         confirm_gap=DEFAULT_CONFIRM_GAP_SECONDS) -> tuple[GridTracker, GridSignal]:
    """Feed one poll. ``offgrid_w`` None = the battery did not answer ES.GetStatus (failed poll)."""
    t = t or GridTracker()
    ts = float(ts)
    offgrid, ongrid = _num(offgrid_w), _num(ongrid_w)
    threshold = float(threshold_w)
    if offgrid is None:
        return replace(t, zero_since=None, failed_since_backup=True, last_ts=ts), GridSignal(pending=t.state == "off")
    if t.last_ts is not None and ts - t.last_ts > confirm_gap:
        t = replace(t, zero_since=None)
    signal = GridSignal()
    if offgrid >= threshold:
        lost = t.state != "off"
        t = replace(t, state="off", off_since=t.off_since if t.state == "off" and t.off_since else ts,
                    on_since=None, zero_since=None, tripped=False, trip_w=None, trip_at=None, confirmed_by=None)
        signal = GridSignal(lost=lost)
    elif ongrid is not None and ongrid < GRID_CHARGE_W:
        was_off = t.state == "off"
        t = replace(t, state="on", off_since=None, on_since=ts if t.state != "on" else (t.on_since or ts),
                    zero_since=None, tripped=False, confirmed_by="grid_charging" if was_off else t.confirmed_by)
        signal = GridSignal(returned_at=ts if was_off else None)
    elif t.state == "on":
        t = replace(t, zero_since=None)
    else:
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


def replay(rows, *, threshold_w=DEFAULT_THRESHOLD_W, confirm_gap=DEFAULT_CONFIRM_GAP_SECONDS, tracker=None):
    """Run :func:`step` over history rows ({ts, offgrid_w, ongrid_w}, any order) → final tracker."""
    for row in sorted((r for r in rows if r.get("ts") is not None), key=lambda r: r["ts"]):
        tracker, _ = step(tracker, row["ts"], row.get("offgrid_w"), row.get("ongrid_w"),
                          threshold_w=threshold_w, confirm_gap=confirm_gap)
    return tracker or GridTracker()


def timeline(rows, *, start, now, threshold_w=DEFAULT_THRESHOLD_W, confirm_gap=DEFAULT_CONFIRM_GAP_SECONDS,
             max_gap=300.0):
    """Merged [{start, end, state}] (epoch seconds) over [start, now]: state on / off / unknown.

    Each span between two consecutive polls ≤ ``max_gap`` apart takes the confirmed state after the earlier poll
    (a single failed poll keeps the last confirmed state); a light return confirmed by 5 min without load is
    back-dated to the start of that run. Longer gaps, time before the first poll and an unconfirmed state are
    ``unknown``. The last poll extends to ``now`` only when it is ≤ ``max_gap`` old.
    """
    points = sorted((r for r in rows if r.get("ts") is not None and r["ts"] <= now), key=lambda r: r["ts"])
    states, tracker = [], None
    for row in points:
        tracker, signal = step(tracker, row["ts"], row.get("offgrid_w"), row.get("ongrid_w"),
                               threshold_w=threshold_w, confirm_gap=confirm_gap)
        if signal.returned_at is not None:
            for i in range(len(states) - 1, -1, -1):  # back-date: the light was on since the zero-load run began
                if states[i][0] < signal.returned_at:
                    break
                states[i] = (states[i][0], "on")
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

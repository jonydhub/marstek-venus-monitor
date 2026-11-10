"""Tray icon: SOC digits on a coloured badge; colour = state; tooltip text (Windows limit 127 chars)."""
from __future__ import annotations

from mvm.snapshot import duration, kwh, view

TOOLTIP_MAX = 127
STATE_COLOURS = {
    "charging": (47, 191, 113),     # green
    "discharging": (245, 165, 36),  # amber
    "no_light": (229, 72, 77),      # red: grid off, running on battery
    "idle": (58, 127, 252),         # brand blue
    "stale": (110, 119, 133),       # grey: no fresh data
    "unknown": (110, 119, 133),
}
STATE_LABELS = {
    "charging": "заряджається", "discharging": "розряджається", "idle": "очікування",
}
_FONT_CANDIDATES = ("segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf")


def icon_state(status, stale: bool) -> str:
    if stale:
        return "stale"
    if not status:
        return "unknown"
    v = view(status)
    if not v.poll_ok:
        return "unknown"
    if v.grid == "off":
        return "no_light"
    if v.direction in ("charging", "discharging", "idle"):
        return v.direction
    return "idle" if v.soc is not None else "unknown"


def icon_text(soc) -> str:
    if soc is None:
        return "?"
    return str(max(0, min(100, int(round(soc)))))


def tooltip(status, stale=False, configured=True) -> str:
    if not configured:
        text = "Marstek Venus Monitor — налаштуйте батарею"
    elif not status or not view(status).poll_ok:
        text = "Marstek Venus Monitor — немає даних"
    else:
        v = view(status)
        head = "Marstek " + " · ".join(p for p in [f"{v.soc}%" if v.soc is not None else None,
                                                  kwh(v.capacity_wh) if v.capacity_wh is not None else None] if p)
        # Light comes only from the backup output of a FRESH poll; without a link it is unknown.
        light = "Світло: невідомо" if stale else {"on": "Світло є", "off": "Світла немає"}.get(v.grid, "Світло: —")
        parts = [light]
        if v.direction == "charging":
            parts.append("заряджається" + (f", до 100% ≈ {duration(v.charge_eta_s)}" if v.charge_eta_s else ""))
        elif v.direction == "discharging":
            eta = v.runtime_s if v.grid == "off" and v.runtime_s else v.discharge_eta_s
            parts.append("розряджається" + (f", до 0% ≈ {duration(eta)}" if eta else ""))
        elif v.direction == "idle":
            parts.append("очікування")
        text = head + "\n" + " · ".join(parts)
        if stale:
            text = "Немає зв’язку з батареєю\n" + text
    return text if len(text) <= TOOLTIP_MAX else text[: TOOLTIP_MAX - 1] + "…"


def _font(size):
    from PIL import ImageFont
    for name in _FONT_CANDIDATES:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size=size)
    except TypeError:  # very old Pillow
        return ImageFont.load_default()


def render_icon(text: str, colour, size: int = 64):
    """Rounded badge in the state colour with white SOC digits; rendered 4x and downsampled for crisp edges."""
    from PIL import Image, ImageDraw
    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.18), fill=tuple(colour) + (255,))
    # digits as large as the badge allows: the tray shows this at 16-24 px
    font_size = int(s * (0.95 if len(text) <= 2 else 0.66))
    font = _font(font_size)
    while font_size > 8:
        box = draw.textbbox((0, 0), text, font=font)
        if box[2] - box[0] <= s * 0.98 and box[3] - box[1] <= s * 0.80:
            break
        font_size -= scale
        font = _font(font_size)
    box = draw.textbbox((0, 0), text, font=font)
    x = (s - (box[2] - box[0])) / 2 - box[0]
    y = (s - (box[3] - box[1])) / 2 - box[1]
    draw.text((x, y), text, font=font, fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)

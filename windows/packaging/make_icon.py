"""Render the app icon (windows/assets/app.ico + PNGs) from the Android launcher vector.

Source: android/app/src/main/res/drawable/ic_launcher_foreground.xml (battery + bolt) on the
launcher background colour (#071522). Re-run after changing the vector:  python windows/packaging/make_icon.py
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "assets"
BACKGROUND = "#071522"
# vector viewport 108x108; group scale 0.72 around (54,53), translateY 1
SCALE, PIVOT, SHIFT_Y = 0.72, (54.0, 53.0), 1.0
CROP = (22.0, 22.0, 86.0, 86.0)  # square around the artwork, so it fills a desktop icon


def _tx(x, y, size):
    gx = PIVOT[0] + SCALE * (x - PIVOT[0])
    gy = PIVOT[1] + SCALE * (y - PIVOT[1]) + SHIFT_Y
    k = size / (CROP[2] - CROP[0])
    return (gx - CROP[0]) * k, (gy - CROP[1]) * k


def _k(size):
    return SCALE * size / (CROP[2] - CROP[0])


def render(size=1024) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * 0.22), fill=BACKGROUND)
    k = _k(size)
    # battery cap: x 39..69, y 18..29, top corners r=4
    x0, y0 = _tx(39, 18, size)
    x1, y1 = _tx(69, 29, size)
    d.rounded_rectangle((x0, y0, x1, y1), radius=4 * k, fill="#234457", corners=(True, True, False, False))
    # battery body: x 28..80, y 27..88, r=9, stroke 2.5
    x0, y0 = _tx(28, 27, size)
    x1, y1 = _tx(80, 88, size)
    d.rounded_rectangle((x0, y0, x1, y1), radius=9 * k, fill="#142A38", outline="#3B6378", width=max(1, round(2.5 * k)))
    d.polygon([_tx(x, y, size) for x, y in ((61, 35), (41, 57), (53, 57), (47, 78), (69, 51), (57, 51))], fill="#3A7FFC")
    d.polygon([_tx(x, y, size) for x, y in ((59, 39), (50, 52), (56, 52))], fill="#8FE9F1")
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    big = render(1024)
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    big.resize((256, 256), Image.LANCZOS).save(OUT / "app.ico", sizes=[(s, s) for s in sizes])
    big.resize((256, 256), Image.LANCZOS).save(OUT / "app-256.png")
    big.resize((512, 512), Image.LANCZOS).save(OUT / "app-512.png")
    print("wrote", OUT / "app.ico")


if __name__ == "__main__":
    main()

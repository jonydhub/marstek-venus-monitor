Screenshots used by the README and the user manual (real data from a Venus E 3.0, direct Wi‑Fi mode):

- `app-main.png` — main screen (top), direct mode.
- `widgets.png` — 4×1, 2×2 and 4×2 widgets (debug preview, `--es showcase live`).
- `onboarding.png` — first-run screen with a discovered battery (IP blurred).
- `server-dashboard.png` — server web dashboard, read-only.

Regenerate with the debug build: `adb shell am start -n com.doloh.marstekwidget/.debug.WidgetPreviewActivity --es showcase live`
(widgets) or `--ez onboarding true` (welcome screen). Keep images ≤ 1080 px wide and < 400 KB; blur IP/MAC/SSID.


Screenshots used by the README and the user manual:

- `app-main.png` — main screen during an outage (debug fixture: `--es showcase outage --ez main true`).
- `widgets.png` — 4×1, 2×2 and 4×2 widgets at Samsung cell sizes (fixture: `--es showcase outage`).
- `journal.png` — event journal (fixture: `--ez journal true`).
- `server-dashboard.png` — server web dashboard (fixture data, read-only).
- `onboarding.png` — first-run screen with a discovered battery (IP blurred).
- `tablet.png` — main screen on a tablet in landscape (1.1.0 layout).

Regenerate with the debug build: `adb shell am start -n com.doloh.marstekwidget/.debug.WidgetPreviewActivity …`
(`--es showcase live` for real data, `--ez onboarding true` for the welcome screen). Keep images ≤ 1080 px wide and < 400 KB; blur IP/MAC/SSID.

# Marstek Energy (Android)

Unofficial, read-only Android app and home-screen widgets for Marstek home batteries (Venus E 3.0 and
compatible devices with the Marstek Open API). Not affiliated with Marstek.

## Modes

- **Напряму по Wi‑Fi (direct, default).** The phone talks to the battery on the home network over the
  Marstek Open API (UDP JSON-RPC, default port 30000). Only `*.Get*` methods are ever sent — the codec
  refuses anything else. «Знайти батарею» broadcasts `Marstek.GetDevice` and sweeps the phone's own /24
  (or a subnet you type in), listing model, IP and firmware. Polling: every ~5 s while the app is open
  (`Bat.GetStatus` + `ES.GetStatus`, plus `ES.GetMode`/`EM.GetStatus` once a minute, ≥1 s between
  requests because the device answers one request at a time and echoes ids as uint16), and ~15 min in
  the background through WorkManager. Away from home the battery is unreachable: the last snapshot is
  kept and marked stale.
- **Через сервер (server).** Reads `GET {base}/api/status` from your own server (see `../server`).
  HTTPS anywhere, plain HTTP only for LAN hosts. Auth: none, `Authorization: Bearer <token>` (token
  encrypted with an Android Keystore key), or Cloudflare Access (in-app WebView login; navigation is
  limited to the configured host and `*.cloudflareaccess.com`).

Both modes produce the same internal status JSON, so widgets, the alert engine and the dashboard
render identically.

## What is computed on the phone (direct mode)

Ported from `server/`: battery power and ETA from the `bat_capacity` trend (linear regression over
5 min, 10 W deadband), 48 h local history (CSV) for the SOC sparkline and the backup-output averages,
grid state from the backup output (draw above the threshold, default 5 W, means the grid is absent),
and reserve runtime/“will it last” using measured discharge, the configured average consumption, or the
measured backup-output average (≥30 min of draw).

## Outage schedules (all of Ukraine)

Per region the app tries sources in order and uses the first that has a published schedule:

1. Direct official API where a stable open one exists: **Львівобленерго** (`api.loe.lviv.ua`) for Lviv
   oblast, **YASNO** public API for Kyiv and Dnipro (ДТЕК/ЦЕК).
2. **ua-outages** — hukpo/ua-outages (MIT), a static aggregate of official operator data
   (`hukpo.github.io/ua-outages/data/now.json`). Distinguishes definite and «можливе» outages and
   emergency days.
3. A public community aggregator as a last-resort fallback (see NOTICE).

DTEK regional websites are behind bot protection and are not scraped. Every source is fetched at most
once per 10 minutes with ETag/If-Modified-Since and an identifying User-Agent. The UI shows the source
used («Джерело: …»). The schedule only informs forecasts; whether there is power right now always comes
from the battery's backup output.

## Build

Requires JDK 21 and Android SDK platform 36 (`sdk.dir` in the git-ignored `local.properties`).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Unit tests cover the UDP codec with sanitized captures from a real device, the subnet scan planner,
flow/ETA/autonomy, history averages, each outage provider, the provider chain, the polite HTTP cache
and window merging.

## Notes

- Battery power is an estimate from capacity change, not a CT reading. Grid power keeps the device's
  raw sign. Cycle counts and PV are not shown (no native counter / no panels assumed).
- Background refresh is limited by Android (15 min minimum, may be deferred).

See `NOTICE` for third-party credits and licenses.

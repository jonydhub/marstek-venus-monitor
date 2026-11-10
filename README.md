# Marstek Venus Monitor

Неофіційний **read-only** моніторинг домашньої батареї **Marstek Venus E** (перевірено на Venus E 3.0): Android-застосунок з віджетами та сповіщеннями + необов'язковий маленький сервер з веб-дашбордом, історією і графіком відключень для всієї України.

> Застосунок і сервер **нічим не керують**: вони лише читають стан батареї через локальний Open API. Потужність батареї й час до 100% / 0% — оцінка за зміною заряду, а не точний вимір.

| Застосунок | Віджети | Дашборд сервера |
|---|---|---|
| ![Головний екран](docs/img/app-main.png) | ![Віджети](docs/img/widgets.png) | ![Дашборд](docs/img/server-dashboard.png) |

<sub>Скриншоти з реальної Venus E 3.0 (режим «Напряму по Wi‑Fi», графік — Львівська область, черга 1.1). Більше — у [посібнику](docs/MANUAL.md).</sub>

## ⬇️ Завантажити

**[Завантажити APK (остання версія)](https://github.com/jonydhub/marstek-venus-monitor/releases/latest/download/Marstek-Venus-Monitor.apk)** — Android 8.0+ · [усі версії](https://github.com/jonydhub/marstek-venus-monitor/releases)

Відкрийте посилання з телефона, встановіть файл (Android попросить дозвіл на встановлення з невідомих джерел) і пройдіть налаштування: [посібник](docs/MANUAL.md).

**Windows 10/11**: `MarstekVenusMonitor-<версія>-setup.exe` (інсталятор без прав адміністратора) або `…-portable.zip` на сторінці [Releases](https://github.com/jonydhub/marstek-venus-monitor/releases) — див. [Windows-програма](#windows-програма).

## Windows-програма

Програма в треї з тим самим сервером усередині: не потрібні Docker, Python чи окремий ПК — достатньо комп'ютера з Windows у тій самій мережі, що й батарея.

| Налаштування й пошук батареї | Дашборд у вікні програми | Значок у треї |
|---|---|---|
| ![Налаштування](docs/img/windows-setup.png) | ![Дашборд](docs/img/windows-dashboard.png) | ![Значки](docs/img/windows-tray-icons.png) |

- **Значок у треї** показує заряд у %, колір — стан: зелений — заряджається, жовтий — розряджається, червоний — світла немає, синій — очікування, сірий — немає даних. Підказка: заряд, kWh, світло, час до 100% / 0%.
- **Меню**: Відкрити дашборд · Налаштування · Запускати з Windows · Доступ для телефона (адреса, токен, QR) · Про програму · Вийти.
- **Перший запуск**: «Знайти батарею» (широкомовний запит + обережне сканування вашої /24 мережі), або IP вручну, «Перевірити з'єднання», регіон і черга, поріг резервного виходу, середнє споживання. Зміни застосовуються без перезапуску.
- **Сповіщення Windows**: світло зникло/повернулось, відключення через 60 і 15 хв, запасу не вистачить, заряд 20% / 10%, заряджання почалось / завершено, температура, немає зв'язку (лише після 3 невдалих опитувань поспіль). Кожне вмикається окремо.
- Дашборд слухає лише `127.0.0.1`; «Доступ для телефона» відкриває його в домашній мережі **тільки з токеном**. Налаштування — `%APPDATA%\MarstekVenusMonitor`, історія й журнали — `%LOCALAPPDATA%\MarstekVenusMonitor`.
- Опитування раз на 30 с (можна збільшити). Якщо батарею вже опитує ваш сервер, краще запускати щось одне з двох.

Збірка з коду: `windows\build.ps1` (Python 3.12+, PyInstaller, Inno Setup 6) — див. [посібник](docs/MANUAL.md#11-windows-програма).

## Два режими

| | **Напряму по Wi‑Fi** | **Сервер** |
|---|---|---|
| Що потрібно | лише телефон із застосунком | ПК / Raspberry Pi / NAS удома, Docker або Python 3 |
| Де працює | тільки в домашній мережі | вдома і поза домом (через VPN / тунель) |
| Історія заряду | лише поки застосунок відкритий | 48 год (SQLite) |
| Сповіщення, коли ви не вдома | ні | так |
| Графік відключень | так (з інтернету) | так, кешує сервер |
| Веб-дашборд (PWA) | ні | так |

Батарея відповідає **лише в локальній мережі** (UDP 30000). Щоб бачити її з мобільного інтернету, потрібен хтось удома, хто опитує її і віддає готовий JSON, — це і є сервер. Детально: [FAQ](docs/MANUAL.md#faq).

```
Venus E ──UDP JSON-RPC (Open API, LAN)──▶ server/ (Python, ПК/RPi/NAS удома)
                                           │  історія 48 год, графік відключень
                                           ▼
                                HTTP /api/status + веб-дашборд
                                           │  (LAN / Tailscale / WireGuard / Cloudflare Tunnel)
                                           ▼
                               Android-застосунок + віджети
```

## Що вміє

- **Заряд, запас kWh, температура, потоки** мережа ↔ батарея ↔ резервний вихід, ETA до 100% / до 0%, графік заряду за 24 год, накопичені лічильники.
- **Світло є / нема** — за потужністю на резервному виході батареї (якщо до нього підключене хоч щось: роутер, ONT, лампа).
- **Графік відключень для всіх областей**: поточне й наступне відключення вашої черги, «можливі» відключення окремо, аварійні відключення, і чи вистачить запасу батареї (і на скільки годин).
- **Android**: головний екран, віджети 4×1, 2×2, 4×2 (і більші — 4×4, 6×3, 6×4 на планшетах), сповіщення (відключення скоро, запасу не вистачить, новий графік, світло зникло/повернулось, заряджання почалось/завершилось, велике споживання, заряд 20% / 10%, температура, дані застаріли).
- **Планшети** (з 1.1.0): екран сам перебудовується за доступною шириною — одна колонка на телефоні, дві на планшеті в портреті, розкладному телефоні чи телефоні в ландшафті, три на планшеті в ландшафті; працює поворот і розділений екран; налаштування — центрована колонка. [Скриншот](docs/img/tablet.png).
- **Сервер**: Python 3 без залежностей, Docker-образ, необов'язковий токен доступу, обмеження частоти запитів.

## Джерела графіків відключень

Сервер і застосунок спершу беруть дані **напряму в оператора**, а агрегат — як запасний варіант (`MARSTEK_OUTAGE_PROVIDER=auto`):

1. **Офіційне джерело напряму**: Львівобленерго (Львівська обл.), YASNO (Київ, Дніпро — ДнЕМ і ЦЕК), сайти Харків-, Полтава-, Запоріжжя- та Чернівціобленерго (розбір портовано з hukpo/ua-outages, MIT; ці сайти відповідають з українських IP).
2. **[hukpo/ua-outages](https://github.com/hukpo/ua-outages)** — статичний агрегат, що кожні ~15 хв читає сайти ~22 операторів.

Для Вінницької області машинного джерела немає (лише картинки й пошук з капчею) — там графік недоступний, а світло визначається за резервним виходом.

Таблиця по областях — у [посібнику](docs/MANUAL.md#джерела-графіків-по-областях). Поле `power_outage.source` у `/api/status` показує, звідки взято графік саме зараз; дашборд пише це у верхній картці.

## Швидкий старт сервера

```bash
git clone https://github.com/jonydhub/marstek-venus-monitor.git
cd marstek-venus-monitor/server
cp docker-compose.example.yml docker-compose.yml   # впишіть MARSTEK_HOST, токен, регіон і чергу
docker compose up -d
```

Без Docker: `cp config.example.env .env`, відредагувати, `python3 -B server.py`. Усі змінні — у [посібнику](docs/MANUAL.md#змінні-середовища).

## Структура

- `server/` — Python 3 (stdlib): опитування батареї, `/api/status`, `/api/regions`, `/health`, веб-дашборд, джерела відключень (`outage.py`, `yasno.py`, `oblenergo.py`, `uaoutages.py`, `regions.py`), історія (`history.py`). Тести: `cd server && python3 -B -m unittest discover -s tests`.
- `android/` — Kotlin, minSdk 26, RemoteViews-віджети, WorkManager.
- `windows/` — програма для Windows (pystray, pywebview, PyInstaller, Inno Setup), що вбудовує `server/` без змін. Тести: `cd windows && python -B -m unittest discover -s tests -t .`.
- `docs/MANUAL.md` — покроковий посібник.

## Обмеження

- Потрібен увімкнений **Open API** у застосунку Marstek; без нього батарея не відповідає.
- «Світла нема» визначається лише якщо від резервного виходу щось живиться (≥ 5 Вт за замовчуванням).
- Графік — те, що публікує оператор; агрегатори можуть запізнюватись. Важливе перевіряйте на сайті свого обленерго.
- Перевірено на одній Venus E 3.0. Інші моделі з тим самим Open API, ймовірно, працюватимуть, але не перевірені.

## Приватність

- Тільки читання: жодних команд керування батареєю.
- Дані батареї залишаються у вас: телефон ↔ батарея або телефон ↔ ваш сервер. Хмари проєкту немає, телеметрії немає.
- В інтернет сервер звертається лише по графік відключень (публічні JSON/сторінки операторів, ua-outages) — без даних батареї.

## Ліцензія та подяки

MIT, див. [LICENSE](LICENSE). Сторонні джерела й ліцензії — [NOTICE](NOTICE): [hukpo/ua-outages](https://github.com/hukpo/ua-outages) (MIT, з нього портовано розбір сайтів чотирьох обленерго), відкриті дані Львівобленерго, публічне API YASNO, сайти Харків-, Полтава-, Запоріжжя- та Чернівціобленерго.

**Проєкт не пов'язаний з Marstek, ДТЕК, YASNO, обленерго чи ua-outages.** Назви й торгові марки належать їхнім власникам. Використовуйте на власний ризик.

---

## English

**Marstek Venus Monitor** is an unofficial, read-only monitor for the Marstek Venus E home battery: an Android app (phone and tablet layouts) with widgets and notifications, plus an optional stdlib-only Python server (Docker image included) that polls the battery's local Open API (UDP JSON-RPC, port 30000), keeps 48 h of history, serves a PWA dashboard and `/api/status`, and merges Ukrainian power-outage schedules — official operator sources first (Lvivoblenergo and YASNO APIs; the Kharkiv, Poltava, Zaporizhzhia and Chernivtsi oblenergo sites, parsers ported from hukpo/ua-outages), then the [hukpo/ua-outages](https://github.com/hukpo/ua-outages) aggregate. It never sends control commands. The battery only answers on the LAN, so remote access needs the home server plus a VPN or tunnel; set `MARSTEK_API_TOKEN` before exposing it anywhere. MIT licensed; not affiliated with Marstek or any grid operator. A Windows 10/11 tray app (`windows/`) embeds the same server in-process: SOC on the tray icon, first-run battery discovery, native dashboard window, Windows notifications, optional token-protected LAN access for phones; per-user installer built with PyInstaller + Inno Setup. Full manual (Ukrainian): [docs/MANUAL.md](docs/MANUAL.md).

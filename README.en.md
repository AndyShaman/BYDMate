<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="BYDMate icon">

# BYDMate

### Trip Logger & Energy Analytics for BYD DiLink 5.0

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/AndyShaman/BYDMate?style=flat-square)](https://github.com/AndyShaman/BYDMate/releases)
[![Sponsor](https://img.shields.io/badge/Sponsor-FF69B4?style=flat-square&logo=githubsponsors&logoColor=white)](SUPPORT.md)

**Real consumption, GPS routes, automation, AI analytics. Local-only, no cloud.**

**English** | [中文](README.zh.md) | [Русский](README.md)

[Features](#features) | [Screenshots](#screenshots) | [Automation](#automation) | [Cluster navigation](#navigation-on-the-cluster) | [AI Insights](#ai-insights) | [ABRP](#abrp--live-telemetry) | [Install](#install) | [Build](#build-from-source) | [Sponsor](SUPPORT.md)

</div>

---

## About

BYDMate is an Android app for the BYD DiLink 5.0 head unit (Leopard 3 / Fangchengbao Tai 3). It logs trips, GPS routes, real energy consumption from the BMS, charging sessions, and provides AI-driven driving analytics. Everything runs locally on the head unit. No cloud, no Google Play Services required.

The stock onboard computer **underestimates consumption by 10-30%**. BYDMate reads data directly from the BMS (energydata SQLite) and shows the real figure. Plus data the stock system does not surface: idle drain, cell balance, trip cost, AI insights.

Two optional features can leave the car: AI insights via OpenRouter and live telemetry to A Better Route Planner. Both are off by default and enabled manually.

---

## What's new in v3.0.0

A major update. BYDMate moved to its own data stack and learned to mirror navigation onto the instrument cluster.

**Own data stack, no D+.** Earlier BYDMate relied on the third-party D+ (迪加) app to reach car data. Now everything is read and every command is sent directly through the car's system service, with no middleman. After installing and verifying BYDMate, D+ can be removed from DiLink. SOC, power, temperatures, capacity, voltage, charge-gun state, SoH and mileage are read from the source. Lights, climate, windows, mirrors and the trunk are controlled directly too.

**Yandex Navigator on the cluster.** The right steering-wheel star moves navigation onto the instrument cluster and back to the center screen. Yandex Navigator is the default, but any app can be picked. See the "Navigation on the cluster" section.

**Sleep charging.** A charge that finished while the car was fully asleep and the app was off is now reconstructed correctly from two SOC points. Such a session could previously be lost.

**Widget and automation.** The widget now shows kilometers per 1% of charge and cabin, battery and 12V temperatures. Automation gained a trigger by exact time and time range with weekday selection, and the rule editor gained a "Run now" button.

---

## Features

| | Feature | Description |
|---|---------|-------------|
| **BMS** | Real consumption | BMS data (energydata), not onboard estimates. Trend over a 25 km rolling window |
| **GPS** | Trip logging | GPS routes, distance, speed |
| **Charge** | Charges | Automatic AC/DC logging, period and lifetime stats, manual add and edit |
| **AI** | AI Insights | Driving analysis via LLM (OpenRouter) |
| **Idle** | Idle drain | Idle consumption from energydata |
| **Bat** | Battery health | Temperature, SoH (on Leopard 3), cell balance, 12V |
| **Map** | Route map | osmdroid (OpenStreetMap) inside trip detail |
| **Rules** | Automation | WHEN→THEN rules: parameter triggers → vehicle commands |
| **Cluster** | Cluster navigation | Mirror navigation onto the instrument cluster via the right star |
| **Widget** | Floating widget | 7-field overlay above other apps: SOC, range, consumption + trend, time, cabin t°, battery t°, 12V |
| **Auto** | Autostart | WorkManager, starts on boot |
| **CSV** | Data export | Trips and charges export to CSV |

---

## Screenshots

### Dashboard

<img src="docs/screenshots/dashboard.jpg" alt="Dashboard" width="800">

Around the SOC ring there are four floating-widget-style fields: trip duration, odometer, cabin temperature on top; current trip distance, estimated range, current trip consumption with a trend arrow on the bottom. The colors and trend logic match the floating widget, so the information reads the same on the home screen and over other apps.

Below the ring: AI insight, a small battery health card (SoH on Leopard 3, temperature, 12V), idle drain, recent trips, period filter.

### AI Insights (expanded)

<img src="docs/screenshots/dashboard-insight-expanded.jpg" alt="AI Insight expanded" width="800">

*LLM driving efficiency analysis: consumption, trends, battery, recommendations*

### Battery health (expanded)

<img src="docs/screenshots/dashboard-battery.jpg" alt="Battery health" width="800">

*Temperature, SoH (on Leopard 3), 12V auxiliary, cell balance, voltages*

### Trips

<img src="docs/screenshots/trips.jpg" alt="Trips accordion" width="800">

*Month > Day > Trip accordion with filters and color-coded consumption*

### Automation

<img src="docs/screenshots/automation.jpg" alt="Automation" width="800">

*WHEN→THEN rules, condition and action editor, trigger configuration*

### Settings

<img src="docs/screenshots/settings.jpg" alt="Settings" width="800">

*Battery, tariffs, currency, AI settings (OpenRouter API), data export*

---

## Automation

The **Automation** tab lets you create rules that drive the car directly through the car's system interface.

### How it works

**WHEN** the condition holds **→ THEN** execute the command.

Examples:
- SOC < 20% → enable recirculation
- Speed > 0 → close the sunshade
- Outside temperature < 0 → enable mirror heat

### Capabilities

| | Description |
|---|-------------|
| **Triggers** | SOC, speed, temperature, doors, windows, tire pressure, drive mode, geofence places, time of day, exact time and time range with weekdays, etc. |
| **Commands** | Windows (including individual driver and passenger), climate, lights, locks, sunroof, mirrors. Directly through the car's system interface |
| **8 action kinds** | Vehicle command, silent or sound notification, app launch, phone call, navigation, URL, Yandex Music |
| **Edge trigger** | Fires only on a false→true transition (not every 3 seconds) |
| **Cooldown** | Configurable delay between firings |
| **Overlay confirmation** | "Cancel / Run" popup before action. 15-second timeout → auto-cancel |
| **Safety** | Windows do not open above 80 km/h, CAN/SHELL commands are blocked |
| **Log** | Full trigger history with outcomes |
| **Templates** | 6 ready-made rules for a quick start |
| **Run now** | Manually run a rule from the editor, bypassing triggers and cooldown |

### Logic

- **AND** — every condition must hold
- **OR** — any one condition is enough
- **Park only** — the rule fires only when the car is in Park

---

## Navigation on the cluster

BYDMate can mirror navigation onto the instrument cluster in front of the driver, so the map and maneuvers are right in your line of sight while the center screen stays free.

### Right steering-wheel star control

- **A short press of the right star** moves navigation to the cluster.
- **Another short press** brings it back to the center screen.
- **Holding the right star** keeps the stock behavior (car menu); BYDMate does not intercept it.

### Enabling

Open **Settings → Cluster** and turn on "Navigator on cluster via right star". The app enables the required service itself (ADB debugging must be on, see "Install"), no manual setup needed.

In the car's stock navigation, enable full-screen cluster mode, otherwise the system has nowhere to render the projection.

### Which app to project

Yandex Navigator is projected by default. Below the enable toggle there is an app picker: choose any installed app (another navigator, a media player, etc.). The new choice applies on the next star press.

### Window size

In **Settings → Display → "Cluster window size"** two sliders (width and height, 50% to 100%) set the projection size. A smaller window is centered, and the native cluster shows through around it.

### How it works

The projection goes through the car's system service: BYDMate raises a virtual display on the instrument panel and moves the chosen app there. To intercept the steering-wheel buttons, the app enables its own accessibility service. It works only while the toggle is on and is used solely for the star. It does not change the firmware or the car itself, and it is fully reversible.

---

## Floating widget

A compact 260×108 dp overlay above other apps. Visible on the map, in media players, and inside BYD apps.

<img src="docs/screenshots/widget-infographic.jpg" alt="Widget legend: what is shown where" width="900">

### What is shown

Seven fields across three rows. Colors: icons gray, values white. The frame and SOC% glow with the status color (whichever of SOC or 12V is worse).

**Top row** (small, 13sp):
- ⏱ **Current trip duration** — `N min` or `X h Y min` (e.g. `47 min`, `1 h 12 min`). Start = ignition on, end = ignition off. Standstills with the car running (parked with AC on, passenger fetching water, red light) belong to the trip — as long as the electrical system is alive, the counter does not reset
- 🚗 **Cabin temperature** — °C

**Center row** (large, key values):
- **SOC %** (18sp bold, colored) — traction battery state of charge. Green > 50%, yellow 20–50%, red < 20%
- **~N km** (28sp, white) — estimated range: `SOC × battery capacity ÷ baseline consumption × 100`. The tilde signals that this is an estimate, not the onboard computer reading. The baseline math is described below in the "Range" subsection
- **X.X ↓** (18sp, trend-colored) — **current trip consumption**, kWh/100km, with a trend arrow (details below)

**Bottom row** (small, 13sp):
- 🔋 **Battery temperature** — °C
- ⚡ **12V** — auxiliary battery voltage, V. Normal 12.5–14.7 V, < 12.0 V = yellow, < 11.7 V = red

### Consumption and trend arrow (right block)

The number on the right is current trip consumption in kWh/100km. It is the energy spent since ignition on, divided by the kilometers driven since the same moment. As you drive, the figure converges to what will be recorded in the trip history: what you see in the widget at the moment you stop is what ends up in the trip card.

**For the first 2 kilometers** the widget blends smoothly from the average consumption of the previous trip to the current one: below 300 m it shows the previous value, from 300 m to 2 km it mixes linearly with the current value, after 2 km only the current value is shown. This avoids the scary 50–60 kWh/100km spikes from cold start and acceleration: while the trip is still short, the already-stable average from the previous trip dominates, and only when the distance becomes representative does the figure switch to the trip's own consumption.

**When parked** (ignition off) the widget shows the average consumption of the last completed trip — the same value it showed in its final moment.

**Range** `~N km` is a weighted blend: 50% from the last completed trip, 30% from the trip before that, and 20% from the one before that (short trips under 3 km are excluded — they are not representative). On longer drives, consumption over the last 10 km of the current trip is added to the mix: its share grows from zero at the first 3 km to half by 25 km. This way the estimate quickly picks up a style change (city tail before a highway, highway back to city), but does not jitter on short trips or AC-on standstills.

**The trend arrow** appears after 2 km and compares a 25 km rolling average against your usual style (mean of the last 10 trips):

- **↓ green** — driving more economically than usual
- **→ white** (straight) — within your usual range
- **↑ yellow** — consumption higher than usual

The arrow does not jump at every red light — it has light inertia: to change color, consumption must clearly differ from the baseline and stay that way for at least a minute.

**What counts as one "trip"** for this number. One ignition cycle: on → off. A standstill with AC on inside the trip is naturally accounted for — the extra kWh land in the divisor. Brief blips (red lights, reconnects) do not split a trip. If DiLink kills the app mid-highway, after restart counting resumes from the real ignition-on moment, not from zero.

### Controls

- **Tap** — open BYDMate
- **Long press (1.5 s)** — hide until BYDMate is opened again
- **Drag to trash** — turn off completely
- Enable, transparency, reset position — in **Settings → Floating widget**

---

## Charges

The **Charges** tab automatically logs every real top-up: list of charges by month, period and lifetime stats, AC and DC filters. Not every plug-in becomes a record: a record is created only if SoC actually rose. If somebody just touched the gun and pulled it out a minute later, nothing lands in the log.

### What counts as a charge

A record is written if either battery capacity or SoC grew during the session. BYDMate tries three data sources in order and takes the first usable one:

1. **Capacity delta** in kWh, if the onboard system reported a refreshed value.
2. **SoC delta** over the active session, converted to kWh by the current battery capacity.
3. **Coarse estimate** from the SoC delta against the nominal capacity, if the first two are empty.

If BYDMate is running during the charge, the record appears immediately. If the plug-in happened before the app launched or the car went into deep sleep, BYDMate catches the record up on the next start as soon as it notices SoC jumped relative to the pre-charge value. Even offline garage charges land in the log.

### How AC vs DC is detected

The charge type is decided by two signals, in priority order:

1. **Gun-state from the onboard system**: gun-state 2 = AC, 3 or 4 = DC. On some BYD models this value is not always present; the next step handles those.
2. **Average session power**: above 15 kW = DC, otherwise AC. AC physically does not exceed 11 kW, DC stations start at 22 kW (CCS slow), so the 15 kW threshold confidently splits the two modes.

The Charges tab has three filters: "All", "AC", "DC".

### Manual add and edit

If a record is missing or the numbers look off:

- **`+ charge` button** in the tab header: add a session manually with date, duration, kWh, tariff.
- **Long-press a record**: an "Edit" / "Delete" menu opens. Edit mode lets you fix any field of an existing charge.

> Feature in active testing. Stable on Leopard 3. On other BYD models automatic detection may misfire: e.g. the onboard system may not report power or gun type, then AC/DC may be wrong. In that case edit records manually and, if possible, send logs to [Issues](https://github.com/AndyShaman/BYDMate/issues).

---

---

## Battery health (SoH)

SoH (State of Health) is the percent "health" of the traction battery, computed by the car's onboard system with its internal algorithms.

On **BYD Leopard 3 (Fangchengbao Tai 3)** BYDMate reads this value directly from the onboard system and shows it in the "Battery health" card. This is the **real SoH from the car**, not an estimate from SoC delta: BYDMate simply reads what the car writes to itself.

On other BYD models access to this value is not yet confirmed, so SoH is hidden there. The rest of the card (battery temperature, 12V, cell balance, min/max voltage) works on every supported model.

If your car exposes SoH and you want to help add support, open an [Issue](https://github.com/AndyShaman/BYDMate/issues) with the model and year of manufacture.

---

## SoH and automatic charge logging (Leopard 3)

On Leopard 3, SoH and automatic charge logging read values directly from the car's onboard system. This works by default, with no switch to flip and no setup step. The first time the app reaches the onboard system, DiLink shows a system **ADB debugging** permission dialog with the key fingerprint. Tap **"Allow"** and check **"Always allow from this computer"** so DiLink does not ask again on every app start.

After that SoH appears in the Battery health card, and charges log automatically with real kWh values.

On cars without onboard-system access (older firmware, non-DiLink) the app falls back gracefully: the rest of BYDMate (trips, consumption, floating widget, automation) works as usual, only SoH and automatic charge logging are unavailable.

---

## If you don't have a Leopard 3

BYDMate is developed and tested on BYD Leopard 3 (Fangchengbao Tai 3). On other BYD models most features still work, with some differences. Before the first launch, check:

- **Trip logging**: on Leopard 3 trips come from the built-in BMS `energydata` database. On models without it (Song, Yuan and similar) BYDMate records trips natively from the live data stream, so the Trips list fills up on its own. Consumption from the SOC delta is a bit coarser than the BMS figure, but the list is no longer empty.
- **Battery capacity**: defaults to 72.9 kWh (Leopard 3). Go to **Settings → Battery** and set your own. For example: Atto 3 = 60.5 kWh, Seal AWD = 82.5 kWh, Han EV = 85.4 kWh. Without this, range and trip-cost calculations will be off.
- **SoH**: shown only on Leopard 3. On other models the "Battery health" card works without the SoH field.
- **Charges**: the AC/DC algorithm was tuned for Leopard 3. On other models records may appear with delay or wrong power, especially for DC. Use manual add and edit when automation misses.
- **Automation and floating widget**: work the same on every model since they use the car's system service.

If something does not work or shows strange values, open an [Issue](https://github.com/AndyShaman/BYDMate/issues) with your car model and DiLink firmware version. We need reports like that to widen support.

---

## Target device

| Parameter | Value |
|-----------|-------|
| Platform | DiLink 5.0 (Android 12, API 32) |
| SoC | Snapdragon 780G |
| Screen | 15.6" landscape, 1920x1200 |
| GMS | No (AOSP without Google Play Services) |
| Tested on | BYD Leopard 3 (Fangchengbao Tai 3) |

---

## How it works

```
BYD energydata (BMS SQLite)  →  HistoryImporter    →  Room DB  →  Compose UI
autoservice (system Binder)  →  TrackingService     ↗     ↓
Android LocationManager      →  TripTracker (GPS)   ↗   AI (OpenRouter)
autoservice (command writes) ←  AutomationEngine   ←  Rules (Room DB)
```

| Data | Source |
|------|--------|
| Consumption, mileage, duration | BYD energydata (BMS) |
| SOC, speed, temperatures | Car system service (autoservice Binder) |
| Cell voltages, 12V, SoH | Car system service (autoservice Binder) |
| GPS coordinates | Android LocationManager |
| AI analytics | OpenRouter API (optional) |
| Vehicle control | Car system service (command writes) |

**No OBD adapter** and **no third-party D+**. BYDMate reads data and controls the car directly through the `autoservice` system service (the same one the stock BYD system uses) under shell access over wireless ADB.

---

## Install

### 1. Enable ADB

Without ADB, BYDMate runs in basic mode. ADB debugging is required for these features:

- **Battery health (SoH)** — precise BMS value instead of a dash.
- **Automatic charge journal** — the app records session start and end. Without ADB, charges can only be added manually.
- **Automation** — triggers and actions (window, climate, light control, etc.). Without ADB the Automation tab does not work.

Without ADB you still get: trip and mileage tracking, energy consumption, widget, AI insights.

These features are on by default, with no switch to flip. The first time the app reaches the onboard system, DiLink shows an "Allow ADB debugging" dialog once, tap **Allow** and check **"Always allow from this computer"**.

- **DiLink 3 / 4** — ADB can be enabled by yourself: install [BydDevelopmentTools](https://disk.yandex.by/d/e3gEnY9P2Y9_fQ), go to *Settings → Version Management*, tap *Reset to factory default* 10 times, enable *Debug Mode when USB is Connected* and *Wireless adb debug switch*. On updated DiLink 3/4 firmware ADB may be locked like on DiLink 5 — then follow the path below.
- **DiLink 5.0** — ADB debugging is **locked** and can only be unlocked remotely from China. This is typically done via **TaoBao** sellers (search for `DiLink 5.0`, ~40 ¥ inside China / ~80 ¥ outside, AliPay payment). The seller remotely opens the engineering menu via the QR code you send, after which ADB is enabled as usual.

  Step-by-step: [PDF guide (Russian)](docs/guides/dilink5-adb-activation-ru.pdf) — included in the repository.

### 2. DiPlus (D+) is no longer required

Since version 3.0.0 BYDMate works directly with the car's system and **does not require** the third-party D+ (迪加) app. All data is read and every command is sent through the car's system service.

If you used earlier versions and installed D+, you can remove it from DiLink once you have verified BYDMate works.

### 3. Install BYDMate

1. Download the BYDMate APK from [**Releases**](https://github.com/AndyShaman/BYDMate/releases)
2. Transfer to DiLink: via USB stick, over network, or via ADB (`adb install BYDMate.apk`)
3. Allow installation from unknown sources if prompted

### 4. First launch

1. Open BYDMate — the setup wizard appears
2. Grant **location** and **storage** permissions (for GPS and reading energydata)
3. Set **electricity tariffs** (for trip cost calculation)

### 5. Background work

**Important:** turn off "Disable background Apps" for BYDMate, otherwise DiLink will kill the app:

<img src="docs/screenshots/dilink-whitelist.jpg" alt="Disable background apps — toggle OFF for BYDMate" width="600">

*DiLink > Settings > General > Disable background Apps > BYDMate = **OFF***

### 6. Configuration (optional)

In **Settings** you can change:
- **Battery capacity** — default 72.9 kWh (Leopard 3)
- **Tariffs** — home (AC) and fast charging (DC), currency
- **Consumption thresholds** — bounds for color coding (green/yellow/red)

---

## AI Insights

BYDMate can analyze your driving statistics with AI (LLM). This is an optional feature — the app fully works without it.

### Setup

1. Sign up at [OpenRouter](https://openrouter.ai/) (free)
2. Create an **API Key** in the OpenRouter dashboard (Keys section)
3. In BYDMate open **Settings** → **AI Insights** block
4. Paste the API key into "OpenRouter API Key"
5. Tap **"Pick model"** — a list of available LLMs opens (free ones included)
6. Tap **"Save and get insight"**

### What gets analyzed

The AI receives anonymized statistics for 7 and 30 days and returns:

- **Facts** — metrics computed from real data (consumption with trend, % of short trips, idle drain)
- **Insights** — correlations, anomalies, and behavioral recommendations from the LLM

The request is sent **once per day**. The result is cached locally. No personal data (GPS, routes) leaves the device — only aggregated statistics.

---

## ABRP — Live Telemetry

BYDMate can send live vehicle data to [A Better Route Planner](https://abetterrouteplanner.com/) (ABRP) via the official Iternio Telemetry API. ABRP uses this data so that the route plan and remaining-range estimate update from your real battery state, instead of average book values.

The feature is **optional**, off by default, and enabled manually in Settings.

### How to get the token

ABRP uses a "Generic Live Data Token" — one token per car in the garage:

1. Open [abetterrouteplanner.com](https://abetterrouteplanner.com/) and sign in.
2. Open your garage and select the car you want live data for. The car must be **saved in the garage**, otherwise the token will not appear.
3. Gear icon → **"Car settings"** → **"Data"** → **"Connect live data"**.
4. From the provider list pick **"Generic"** and tap **"Link"**. A long token string appears — this is the `User Token`.

**If "Generic" is not in the list**: switch the car model code in the ABRP garage to any popular BYD model (e.g. BYD Atto 3 or BYD Seal), save, and Generic will appear. After linking the token, the model code can be switched back.

### Setup in BYDMate

1. **Settings** → **"ABRP — telemetry"** block.
2. Paste the token into the **"Live data token from ABRP"** field.
3. Optional: ABRP model code (if you know your car's exact code in the ABRP library) and the send interval (5–120 s, default 12 s — Iternio's recommended value).
4. Tap **"Save ABRP"**, then toggle **"Live data → A Better Route Planner"** on. Without a saved token the toggle stays disabled.
5. The ABRP app on DiLink (or the browser on your phone) will now see real-time SOC, power, temperatures, charge.

### What is sent

Only aggregated vehicle metrics, no identifiers:

- **SOC** — current traction battery percent
- **Speed** — km/h
- **Power** — current traction power (negative while charging, as Iternio requires)
- **Battery / cabin / exterior temp** — battery, cabin, and outside temperatures
- **Capacity** — nominal battery capacity
- **Odometer** — mileage, km
- **Tire pressures** — pressure in 4 tires
- **is_charging / is_parked** — state flags
- **is_dcfc / kwh_charged** — charge station type (DC vs AC) and kWh in the current session (sent when the car exposes onboard-system data, otherwise these fields are simply omitted)
- **soh** — real battery SoH (Leopard 3)

### What is NOT sent

- **No GPS coordinates.** ABRP runs as a separate Android app right on DiLink and reads its own location from the OS. Duplicating coordinates over a third-party channel would just leak position to an external server.
- Also not sent: VIN, device ID, trip history, routes, user settings.

### How ABRP computes remaining range

ABRP picks a forecast from its model library plus telemetry: current SOC, battery temperature, driving speed, wind, road profile, elevation. BYDMate does not send its own "estimated range" — ABRP has its own, more accurate route-aware estimate that also factors in weather and elevation.

---

## Build from source

```bash
# Requires: JDK 17, Android SDK 34
git clone https://github.com/AndyShaman/BYDMate.git
cd BYDMate
./gradlew assembleDebug
```

---

## Tech stack

- **Kotlin** 2.1 + **Jetpack Compose** + Material 3
- **Room** (SQLite) + **Hilt** (DI) + **OkHttp**
- **osmdroid** (OpenStreetMap) + **Coroutines/Flow**
- Min SDK 29 / Target SDK 29 / Compile SDK 34

---

## Credits

- **[BYD Trip Info](https://www.byd-seal-forum.de/forum/thread/1811-byd-trip-info-app/)** (`org.jayb.bydapp`) by jayb — the original DiLink trip app, inspiration for BYDMate
- **[DiPlus](https://www.dilink.cn/)** (迪加) by Van Design — the bridge app for car data, used in early BYDMate versions (no longer required since 3.0.0)

---

## Sponsor the project

The project is non-commercial, built as a hobby. If you want to say thanks, the details are in [SUPPORT.md](SUPPORT.md). If not, thanks anyway for your trust.

---

## License

**GPLv3** with attribution clauses.
See [LICENSE](LICENSE) for details.

Copyright (C) 2026 [AndyShaman](https://github.com/AndyShaman)

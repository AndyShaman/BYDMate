<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="BYDMate icon">

# BYDMate

### Trip Logger & Energy Analytics for BYD DiLink 5.0

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-PolyForm_Noncommercial-blue?style=flat-square)](LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/AndyShaman/BYDMate?style=flat-square)](https://github.com/AndyShaman/BYDMate/releases)
[![Sponsor](https://img.shields.io/badge/Sponsor-FF69B4?style=flat-square&logo=githubsponsors&logoColor=white)](SUPPORT.md)

**Real consumption, GPS routes, automation, AI analytics. Local-first; cloud features are optional.**

Split screen 1/3 and 2/3, navigation on the instrument cluster, Yandex Navigator guidance on the windshield, turn-signal blind-spot cameras, a Russian voice assistant, ABRP telemetry.

Tested on Leopard 3 (Fangchengbao Tai 3), with support for Sea Lion 07, Song, Atto 3, Seal, Han. DiLink 3.0, 5.0, 5.1, and firmware with the new UI7 interface (OTA V1.6).

**English** | [中文](README.zh.md) | [Русский](README.md)

[Features](#features) | [Screenshots](#screenshots) | [Automation](#automation) | [Projection to cluster](#projection-to-cluster) | [HUD](#hud) | [Split screen](#split-screen) | [AI Insights](#ai-insights) | [ABRP](#abrp--live-telemetry) | [Install](#install) | [Build](#build-from-source) | [Sponsor](SUPPORT.md)

</div>

---

## About

BYDMate is an Android app for the BYD DiLink 5.0 head unit (Leopard 3 / Fangchengbao Tai 3). It logs trips, GPS routes, real energy consumption from the BMS, charging sessions, and provides AI-driven driving analytics. No Google Play Services required.

The stock onboard computer **underestimates consumption by 10-30%**. BYDMate reads data directly from the BMS (energydata SQLite) and shows the real figure. Plus data the stock system does not surface: idle drain, cell balance, trip cost, AI insights.

Core features (trips, charges, automations, local insights, offline voice agent) run entirely on-device and require no internet. Cloud features are off by default and enabled only by entering a key. What leaves the device and when is described in [Data & Network](#data--network).

---

## What's new

The main things BYDMate gained after version 3.0.

**Split screen on UI7 firmware.** After a firmware update, the car itself splits the screen: the 1/3 and 2/3 panes, the divider, and the swap gesture are native. BYDMate only launches the chosen pair of apps. On such firmware the mechanism choice does not appear in Settings - there is only one.

**"Steering-wheel key" trigger.** Any steering-wheel key can be mapped to its own action: Automation, add trigger, "Steering-wheel key", press the key you want in learning mode. Disable or delete the rule and the key goes back to its factory function.

**Navigation guidance on the windshield (HUD).** The maneuver, the distance to it, the street name, the arrival time and the speed limit sign are drawn on the factory head-up display. Guidance keeps running even when the navigator is minimized or projected to the cluster. Requires a car equipped with a HUD. See the "HUD" section.

**Split screen 1/3 + 2/3.** Two apps on screen at once: the navigator on the wide side, music or a messenger on the narrow one. Launched from the widget, from automations, or by voice. If BYDMate windows are not available on your firmware, there is a "Native split" mode that lets the firmware itself do the split. See the "Split screen" section.

**Blind-spot cameras.** With the turn signal on, the side camera picture appears by itself on the cluster or in a small window on the screen. If a car is detected behind and to the side, the window is highlighted with an orange frame.

**Russian voice AI agent.** Speech recognition runs right in the car, with no internet. Simple commands run instantly; everything else goes to the AI agent, which reads car data, controls the body and climate, builds routes, runs automations and answers by voice. The agent is **Russian-only by design** - the stock BYD assistant already covers English and Chinese, so BYDMate fills the language the car lacks. It is documented in full in the [Russian README](README.md#голосовой-ai-агент).

**Manual range calculation.** If the automatic estimate from trip history does not suit you, the battery settings let you enter your own consumption table by battery temperature.

---

## Features

| | Feature | Description |
|---|---------|-------------|
| **BMS** | Real consumption | BMS data (energydata), not onboard estimates. Trend over a 25 km rolling window |
| **GPS** | Trip logging | GPS routes, distance, speed |
| **Charge** | Charges | Automatic AC/DC logging, period and lifetime stats, manual add and edit |
| **AI** | Insights | Driving analysis: on-device rules (default) or LLM via OpenRouter |
| **TRIP** | TRIP 1 / TRIP 2 counters | Distance, kWh (including parked drain), driving time, and cost since last reset; long-press to reset, tap for details |
| **Bat** | Battery health | Temperature, SoH (on Leopard 3), cell balance, 12V |
| **Map** | Route map | osmdroid (OpenStreetMap) inside trip detail |
| **Rules** | Automation | WHEN→THEN rules: parameter triggers → vehicle commands |
| **Cluster** | Projection to cluster | Mirror the selected app onto the instrument cluster via a steering-wheel button (right star by default) |
| **HUD** | Head-up display | Maneuvers, distance and the speed limit sign from Yandex Navigator on the factory head-up display |
| **Split** | Split screen | Two apps at once: BYDMate windows in a 1/3 and 2/3 layout, or the firmware's own split |
| **Cam** | Blind-spot cameras | The camera of the matching side while the turn signal is on: on the cluster or in a small window on the screen |
| **Voice** | Voice agent | Russian offline assistant: on-device speech recognition, AI agent (32 tools), neural TTS |
| **Widget** | Floating widget | 7-field overlay above other apps: SOC, range, consumption + trend, time, cabin t°, battery t°, 12V |
| **Auto** | Autostart | WorkManager, starts on boot |
| **CSV** | Data export | Trips and charges export to CSV |

---

## Screenshots

### Dashboard

<img src="docs/screenshots/dashboard.jpg" alt="Dashboard" width="800">

Around the SOC ring there are four floating-widget-style fields: trip duration, odometer, cabin temperature on top; current trip distance, estimated range, current trip consumption with a trend arrow on the bottom. The colors and trend logic match the floating widget, so the information reads the same on the home screen and over other apps.

Below the ring: AI insight, a small battery health card (SoH on Leopard 3, temperature, 12V), two TRIP 1 / TRIP 2 counters, recent trips, period filter. Each counter tracks distance, kWh consumption (including parked drain), driving time, and cost since last reset. Long-press a counter to reset it; tap for a details popup.

### AI Insights (expanded)

<img src="docs/screenshots/dashboard-insight-expanded.jpg" alt="AI Insight expanded" width="800">

*Driving efficiency analysis — consumption, trends, battery, recommendations (local or LLM)*

### Battery health (expanded)

<img src="docs/screenshots/dashboard-battery.jpg" alt="Battery health" width="800">

*Temperature, SoH (on Leopard 3), 12V auxiliary, cell balance, voltages*

### Trips

<img src="docs/screenshots/trips.jpg" alt="Trips accordion" width="800">

*Month > Day > Trip accordion with filters and color-coded consumption*

### Automation

<img src="docs/screenshots/automation-en.jpg" alt="Automation" width="800">

*WHEN→THEN rules, condition and action editor, trigger configuration*

### Settings

<img src="docs/screenshots/settings.jpg" alt="Settings" width="800">

*Battery, tariffs, currency, insight source (local / OpenRouter), data export*


### Split screen

<img src="docs/screenshots/split-screen.jpg" alt="Split screen: Yandex Music left, Yandex Navigator right" width="800">

*Two panes, 1/3 and 2/3, with the BYDMate widget on top*


### Blind-spot camera on the cluster

<img src="docs/screenshots/blindspot-cluster.jpg" alt="Left camera on the instrument cluster with the left turn signal on" width="800">

*Left turn signal: the left camera feed on the instrument cluster*

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
| **Triggers** | SOC, speed, temperature, doors, windows, sunroof, tire pressure, drive mode, geofence places, time of day, exact time and time range with weekdays, steering-wheel key, widget tap, voice phrase, service start, internet available, etc. |
| **Commands** | Windows (including individual driver and passenger), climate, lights, locks, sunroof, mirrors. Directly through the car's system interface |
| **17 action kinds** | Vehicle command, notification, app launch, phone call, route in Yandex Navigator, open URL, Yandex Music, pause, media volume, sentry mode, Wi-Fi hotspot, speak text, agent query, projection to cluster, split screen (start, close, toggle) |
| **Edge trigger** | Fires only on a false→true transition (not every 3 seconds) |
| **Cooldown** | Configurable delay between firings |
| **Overlay confirmation** | "Cancel / Run" popup before action. 15-second timeout → auto-cancel |
| **Safety** | Windows do not open above 120 km/h, sunroof above 80 km/h, door unlock blocked above 30 km/h; CAN/SHELL commands are blocked |
| **Log** | Full trigger history with outcomes |
| **Templates** | 6 ready-made rules for a quick start |
| **Run now** | Manually run a rule from the editor, bypassing triggers and cooldown |

### Logic

- **AND** — every condition must hold
- **OR** — any one condition is enough
- **Park only** — the rule fires only when the car is in Park

---

## Projection to cluster

BYDMate can mirror the selected app (navigation by default) onto the instrument cluster in front of the driver, so the map and maneuvers are right in your line of sight while the center screen stays free.

### Steering-wheel button control

- **A short press of the chosen button** moves the app to the cluster.
- **Another short press** brings it back to the center screen.
- **Holding the right star** keeps the stock behavior (car menu); BYDMate does not intercept it.

### Enabling

Before the first use, set up the stock navigation output to the cluster once, otherwise the system has nowhere to render the projection.

**1.** On the head unit's home screen, tap the cluster-projection icon (bottom-left, below the star icon). The stock navigation map appears on the cluster.

<img src="docs/screenshots/cluster-projection-icon.jpg" alt="Cluster-projection icon on the home screen" width="800">

**2.** On the map that appears, tap the **IPC** button in the bottom bar until the cluster switches to full-screen mode.

<img src="docs/screenshots/cluster-projection-ipc-full.jpg" alt="The IPC button switches the cluster to full-screen mode" width="800">

**3.** In BYDMate, open **Settings → Display** and turn on "Cluster projection". The app enables the required service itself (ADB activation done during install is required, see "Install"), no manual setup needed.

After that, a short press of the chosen steering-wheel button (right star by default) moves the selected app to the cluster and back.

<img src="docs/screenshots/settings-display-en.jpg" alt="Settings → Display: cluster projection, what each option does" width="800">

### Projection modes: Factory and Extended

In **Settings → Display**, next to the projection toggle, you choose the projection mode. There are two, and they differ in whether the app touches the car's system settings.

**Factory (default).** The app does not change a single system setting: the projection works through a virtual display (the picture is mirrored onto the cluster), and the window adjustment sliders work more precisely. Installs and uninstalls without a trace. Limitation: the voice agent and the HUD cannot see the navigator screen during projection; the agent takes maneuver guidance from the Navigator notification only.

**Extended.** The navigator is launched as a window directly on the instrument-cluster display, so the voice agent and the HUD see the route during projection. For this the app enables the system freeform-window setting (enable_freeform_support). The mode is enabled only through a confirmation dialog, and a one-time head-unit reboot is required afterwards (long-press the volume knob). The new mode applies on the next projection start.

**How to restore factory settings.** Switch the mode back to Factory: the app immediately writes the system setting back to its factory value, and a DiLink reboot completes the rollback. After that the system is in the same state as before BYDMate was installed.

If you used the projection in versions before 3.7, your previous behavior is kept automatically on update: the mode stays Extended. For those who never enabled the projection, the app never touches system settings at all.

### Which app to project

Yandex Navigator is projected by default. Below the enable toggle there is an app picker: choose any installed app (another navigator, a media player, etc.). The new choice applies on the next star press.

### Window size and position

**Settings → Display → "Cluster window size"** has five sliders. If the projection already fills the whole cluster on your car (Leopard 3, for example), there is nothing to adjust: the defaults give the same full-screen behavior as before.

- **Width** and **Height** (20-100%) - the window size as a percentage of the cluster. A smaller window frees up space, and the native cluster shows through around it.
- **Horizontal offset** (0-100%) - window position: 0 = left edge, 50 = center, 100 = right edge. Only applies when width is below 100%.
- **Vertical offset** (0-100%) - the same for the vertical axis: 0 = top, 50 = center, 100 = bottom.
- **Scale** (50-150%) - the size of the projected app's interface: the lower the value, the smaller the text and the more of the map fits in the window.

Changes apply immediately, right during the projection - move a slider and watch the cluster.

### Cars with a mini cluster window (Sea Lion 07 and similar)

On some models the stock cluster shows the projection not full-screen but in a fixed zone (on Sea Lion 07 - the right third). A full-screen window gets cropped there. Fix: shrink the window and move it into the visible zone with the sliders above.

Values confirmed by a Sea Lion 07 owner: **width 32%, height 92%, horizontal offset 100%, vertical offset 6%, scale 90%**.

<img src="docs/screenshots/cluster-mini-settings.jpg" alt="Window settings for Sea Lion 07" width="800">

<img src="docs/screenshots/cluster-mini-view.jpg" alt="Yandex Navigator in the Sea Lion 07 mini cluster zone" width="800">

You cannot make the window fill the whole cluster on these cars: the display zone is set by the cluster's own firmware and cannot be controlled.

### How it works

The projection runs in one of the two modes described above. In Factory mode BYDMate creates a virtual display and mirrors the chosen app into it without changing any system settings. In Extended mode the app is launched as a window directly on the instrument-cluster display, so the voice agent and the HUD can see the route during projection. To intercept the steering-wheel buttons, the app enables its own accessibility service. It works only while the toggle is on and is used solely for the star. It does not change the firmware or the car itself, and it is fully reversible.

---

## HUD

If the car has a factory head-up display, BYDMate draws Yandex Navigator guidance on the windshield: the maneuver icon, the distance to it, the street name and the arrival time. A separate toggle adds the speed limit sign, sent as a number. While the Navigator warns about a speed camera, the camera icon and the distance to it replace the maneuver arrow. Guidance keeps running even when the Navigator is minimized or projected to the cluster; Yandex Maps works as a guidance source alongside the Navigator.

Enable it in **Settings → Display**, section "HUD (head-up display)": the "Navigation on HUD" toggle and, separately, "Speed sign under the arrow". Guidance travels over the HUD's own factory channel, so a car equipped with a head-up display is required. If the car has no such channel, the app says so in Settings and does not enable the feature.

---

## Blind-spot cameras

With the turn signal on, BYDMate shows the side camera picture by itself: the left turn signal puts the left camera on the cluster, the right one shows a small window on the main screen. If the sensor sees a car behind and to the side, the window is highlighted with an orange frame.

<img src="docs/screenshots/blindspot-cluster.jpg" alt="Blind-spot camera on the cluster" width="800">

Enable it in **Settings → Display**, section "Blind spots" (off by default). The same place holds the speed threshold below which the camera is not shown, the window width and its position: the "Set position" button shows an empty window that you drag with a finger to wherever the camera should appear. Requires a car equipped with a surround-view system.

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

This is the automatic estimate. If it does not suit you, **Settings → Auto & Battery → "Range calculation method"** offers a "Manual" method: your own 5-point table of battery temperature from +20 to -20 °C and the consumption at it, optionally with the range at 100% charge.

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
- Enable, transparency, reset position — in **Settings → Widget**

---

## Split screen

Two apps on screen at once: one takes two thirds, the other one third. A typical pair is a navigator on the wide side and music or a messenger on the narrow one. Sides can be swapped, apps can be changed on the fly, and the chosen pair is remembered.

<img src="docs/screenshots/split-screen.jpg" alt="Split screen 1/3 + 2/3" width="800">

### Enabling

1. Open **Settings → Split screen** and turn on the "Split screen 1/3 + 2/3" toggle.
2. If a note about rebooting appears under the toggle, reboot the head unit once (long-press the volume wheel). If you already use the "Extended" projection mode, no reboot is needed - everything required is already active.

While the feature is off, the app does not touch any system settings. Turning the toggle off restores the setting to its factory value - unless the "Extended" projection mode also needs it: in that case the setting returns to factory once you turn that mode off too.

<img src="docs/screenshots/settings-split-en.jpg" alt="Settings → Split screen" width="800">

### Split mechanism

Under the toggle you choose what splits the screen: "BYDMate windows" (default) or "Native split".

- **BYDMate windows** - the 1/3 and 2/3 layout with the control pill described below.
- **Native split** - the car firmware splits the screen itself. This works even where BYDMate windows are unavailable (DiLink 5.1). The look depends on the firmware: where thirds are supported it opens 1/3 and 2/3 panes, elsewhere it splits the screen in half and the second app is picked in a system window.

On firmware with the new UI7 interface the mechanism choice does not appear: the car itself performs the split there, the 1/3 and 2/3 panes are native, and no head-unit reboot is needed to turn it on.

### Launching

Three ways. The "?" badge next to the section header in Settings shows this right in the car.

- **From the widget** (the easiest). In Settings, under "Widget tap", enable "Tap zoning" and set "Left tap action" to "Launch split screen". A tap on the left third of the widget now opens the split; another tap closes it.
- **From automations.** Rules have three actions: "Split Screen", "Close Split Screen" and "Toggle Split Screen". For example, start the split whenever navigation launches.
- **By voice.** Ask the agent to turn on split screen.

### First launch and choosing apps

On the first launch the app asks what to show: first you pick the app for the wide part (2/3), then for the narrow one (1/3). The pair is remembered and opens right away next time. You can change apps via the pill or in Settings; the "Reset last pair" button lives there too.

### The control pill

While the split is active, a small pill sits at the bottom of the screen. Tapping it opens a menu: swap sides, swap apps, pick a different app for the left or right pane, exit to normal full screen. When the split closes, the pill disappears on its own.

### How it coexists with everything else

- If something fullscreen takes over (reverse gear, cameras, video), the split closes by itself. Bring it back the usual way - a widget tap or a voice command.
- The Back button from BYDMate returns you to the split.
- If you start the native DiLink split screen, ours ends cleanly - two splits cannot run at once.

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
- **Battery capacity**: defaults to 72.9 kWh (Leopard 3). Go to **Settings → Auto & Battery** and set your own. For example: Atto 3 = 60.5 kWh, Seal AWD = 82.5 kWh, Han EV = 85.4 kWh. Without this, range and trip-cost calculations will be off.
- **SoH**: shown only on Leopard 3. On other models the "Battery health" card works without the SoH field.
- **Charges**: the AC/DC algorithm was tuned for Leopard 3. On other models records may appear with delay or wrong power, especially for DC. Use manual add and edit when automation misses.
- **Automation and floating widget**: work the same on every model since they use the car's system service.

If something does not work or shows strange values, open an [Issue](https://github.com/AndyShaman/BYDMate/issues) with your car model and DiLink firmware version - and attach your car's parameter catalog: **Settings → Service & Data → "Save fid catalog"**. A `fid-dump-….txt` file will appear in the Download folder. It contains technical parameter identifiers only - no VIN, no location, no personal data.

Why this matters. Different BYD models use different parameter sets: a command that turns on seat ventilation on a Leopard 3 may have a different number on a Song, or not exist at all. That is exactly why some functions fail on other cars. The catalog from your car shows how the parameters are named and numbered on your exact firmware - so we fix things using your car's data instead of guessing.

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
Android LocationManager      →  TripTracker (GPS)   ↗   LocalInsightEngine / OpenRouter
autoservice (command writes) ←  AutomationEngine   ←  Rules (Room DB)
```

| Data | Source |
|------|--------|
| Consumption, mileage, duration | BYD energydata (BMS) |
| SOC, speed, temperatures | Car system service (autoservice Binder) |
| Cell voltages, 12V, SoH | Car system service (autoservice Binder) |
| GPS coordinates | Android LocationManager |
| AI analytics | On-device rules (default) or OpenRouter API (optional) |
| Vehicle control | Car system service (command writes) |

**No OBD adapter** and **no third-party D+**. BYDMate reads data and controls the car directly through the `autoservice` system service (the same one the stock BYD system uses) under shell access over wireless ADB.

---

## Data & Network

| Feature | Destination | What is sent | When active |
|---|---|---|---|
| Cloud AI Insights | OpenRouter | Aggregated trip and charge statistics for 7/30 days | Only after entering an API key |
| Voice agent (LLM) | OpenRouter / z.ai / your server | Command text, vehicle state (SOC, climate, etc.), GPS coordinates for navigation requests | Only after configuring a provider |
| Agent web search | Exa / z.ai / OpenRouter | Search query text | Key entered and agent invoked the tool |
| Weather (agent) | Open-Meteo | GPS coordinates or place name (geocoding) | Agent invoked the tool |
| Charging stations (agent) | Overpass (overpass-api.de / maps.mail.ru) | GPS coordinates | Agent invoked the tool |
| Online TTS voices | MiniMax / fal.ai / Replicate / OpenRouter | Text of the spoken response | Only if an online voice is selected |
| Trip detail map | OpenStreetMap tile servers (or Amap, if selected in settings) | Coordinates of the visible map area | When the trip map or place editor is opened |
| Update check | GitHub API | Nothing (fetches release list; version comparison is local) | Automatically |
| ABRP live telemetry | abetterrouteplanner.com | SOC, power, speed, temperatures, odometer, tire pressures, charging status, battery capacity, SoH, kWh charged in current session, car model (GPS is intentionally not sent) | Only after entering a token |
| Telemetry webhook | Settings → Integrations → Webhook | The same JSON as ABRP, POST to your URL every 1/8/30 s (driving/charging/parked); GPS coordinates only via a separate toggle | Off by default |

Without any keys configured, only the update check (GitHub) and map tiles when viewing a trip route leave the device, as well as voice model downloads on explicit user request (github.com). API keys are stored locally in the app's database and are never sent anywhere other than the provider itself.

---

## Install

### 1. Enable ADB

Without ADB, BYDMate works as an offline trip logger: history from the stock database, the route map, CSV export, AI insights on data already collected.

Enabled ADB debugging is required for everything that reads or writes vehicle parameters: live figures in the widget and dashboard (SOC, range, temperatures, 12V), SoH, automatic charge logging, automation, projection to cluster, split screen, HUD guidance, blind-spot cameras, voice control of the car, and log recording.

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
2. Grant **location** and **storage** permissions. As you turn features on, the app will also ask for: the microphone for the voice agent, the camera for blind-spot cameras, contacts for calling by name, display-over-other-apps for the widget and notifications, and usage-access for detecting the active app
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

## Vehicle System

**Settings → Service & Data → Vehicle System** has two items that change the car's own behavior, not the app's.

**Steering-wheel volume knob.** A toggle that makes a press pause and resume playback instead of switching the audio source. Needed after a firmware update that changed the press to switch source. Off by default.

**Car interface language.** UI7 firmware only. The button opens BYD's hidden stock language dialog with the full list, including Russian, Ukrainian, and Polish. The firmware itself changes the language; the app writes nothing. On some cars, auto-parking stops working in Russian - switch back to English with the same button if that happens.

---

## AI Insights

The dashboard card analyzes your stats for the last 7 days (vs the previous 7) and shows a title, short summary, metrics table, and up to **5 recommendations**. **No internet** is required in the default mode.

### Two modes

| Mode | Where to enable | Internet | API key |
|------|-----------------|----------|---------|
| **Local (offline)** | Default | Not required | Not required |
| **OpenRouter (cloud)** | Settings → Integrations → "OpenRouter (cloud)" | Required | OpenRouter API Key |

Use the **"Analysis source"** switch in **Settings → Integrations**. In local mode, tap **"Refresh insight"** for a manual recalc. Auto-refresh is **once per day** (cached on device).

### Data sources

Everything is computed from the local Room database — **no GPS, routes, or personal identifiers**:

- **Trips** — km, kWh, avg consumption, speed, short trips (&lt; 5 km), best/worst by consumption, cost
- **Engine-on idle** — kWh and hours (`idle_drains`)
- **Night idle** — sessions that started **10pm–6am**
- **Charging** — AC/DC kWh, session count, price per kWh, weekly charge cost
- **12V** — current voltage and weekly trend (up to 7 days of history)
- **Cells** — max−min spread in mV from live vehicle data
- **Temperature** — average exterior temp across trips

**Minimum for analysis:** at least **5 trips** in the last 7 days. Otherwise the card shows "Not enough data to analyze".

### Card layout

1. **Title and summary** — one main weekly message from the highest-priority rule (see below).
2. **Dynamics** — week-over-week table: consumption, trips, % short trips, avg distance, engine-on idle, night idle (local mode only).
3. **Recommendations** — up to 5 bullets from matching rules, sorted by priority.

Local logic lives in `LocalInsightEngine.kt`: deterministic thresholds + string templates (`local_insight_*`). Localized into 6 languages (ru / en / zh / pt / pl / be).

### Title priority (local mode)

| Priority | Condition | Example title |
|----------|-----------|---------------|
| 90 | 12V &lt; 11.8 V | "12V critically low" |
| 85 | Cell spread &gt; 50 mV | "Large cell spread" |
| 80 | Consumption up &gt; 15% vs last week | "Consumption up 15%" |
| 78 | Night drain ≥ 4 kWh and ≥ 35% of idle | "Night idle drain" |
| 70 | Consumption up &gt; 5% | "Consumption up 8%" |
| 60 | Consumption down &gt; 5% | "Consumption down 10%" |
| 10 | Otherwise | "Consumption stable" |

### Recommendations (bullets)

Many rules are evaluated in parallel; the **top 5** by priority are shown. Topics include:

| Topic | When it fires |
|-------|---------------|
| Night idle | ≥ 0.3 kWh at night with engine on |
| DC more expensive than AC | Both ≥ 3 kWh, DC 15%+ pricier |
| Heavy DC use | ≥ 2 DC sessions, DC &gt; AC×1.5 |
| Short trips | ≥ 40% of trips under 5 km |
| Engine-on idle | ≥ 2 kWh while parked |
| 12V low / falling | 11.8–12.4 V or downward weekly trend |
| Cell spread | 31–50 mV |
| Winter + higher consumption | ≤ 5°C and consumption up |
| Best vs worst trip | Spread ≥ 5 kWh/100 |
| Consumption improved | Down &gt; 5% vs last week |
| Highway driving | Avg speed ≥ 75 km/h |
| High / low consumption | ≥ 28 or ≤ 16 kWh/100 |
| More mileage / trips | +20% vs last week |
| Heat + A/C | ≥ 25°C, consumption ≥ 22 |
| Mixed charging | AC and DC in the same week |
| Cost | Currency per 100 km or weekly charging cost |
| Good habits | Low night/day idle share, healthy 12V/cell balance |

Full rules and thresholds: `LocalInsightEngine.kt` and `LocalInsightEngineTest.kt`.

### Cloud mode (OpenRouter)

Optional: an LLM generates text instead of templates. Setup:

1. Sign up at [OpenRouter](https://openrouter.ai/) (free)
2. Create an **API Key** in the OpenRouter dashboard (Keys section)
3. In BYDMate: **Settings → Integrations** → **"OpenRouter (cloud)"** mode
4. Paste the API key into "OpenRouter API Key"
5. Tap **"Pick model"** — list of LLMs (free ones included)
6. Tap **"Save and get insight"**

In cloud mode only **aggregated statistics** are sent (same metrics as local rules) — no GPS or routes. Request **once per day**, response cached locally. The dynamics table is built the same as in local mode; the LLM supplies title, summary, and recommendation wording.

---

## ABRP — Live Telemetry

BYDMate can send live vehicle data to [A Better Route Planner](https://abetterrouteplanner.com/) (ABRP) via the official Iternio Telemetry API. ABRP uses this data so that the route plan and remaining-range estimate update from your real battery state, instead of average book values.

<img src="docs/screenshots/settings-integrations-en.jpg" alt="Settings → Integrations: ABRP" width="800">

The feature is **optional**, off by default, and enabled manually in Settings.

### How to get the token

ABRP uses a "Generic Live Data Token" — one token per car in the garage:

1. Open [abetterrouteplanner.com](https://abetterrouteplanner.com/) and sign in.
2. Open your garage and select the car you want live data for. The car must be **saved in the garage**, otherwise the token will not appear.
3. Gear icon → **"Car settings"** → **"Data"** → **"Connect live data"**.
4. From the provider list pick **"Generic"** and tap **"Link"**. A long token string appears — this is the `User Token`.

**If "Generic" is not in the list**: switch the car model code in the ABRP garage to any popular BYD model (e.g. BYD Atto 3 or BYD Seal), save, and Generic will appear. After linking the token, the model code can be switched back.

### Setup in BYDMate

1. **Settings → Integrations** → **"ABRP — telemetry"** block.
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

The same data sent to ABRP can also be posted to your own server: see the telemetry webhook under Settings → Integrations.

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

## Troubleshooting

If something does not work, follow these steps — most issues are solved by the first one, and if not, you will help us find the cause quickly.

**1. Reboot the head unit.** Press and hold the volume wheel until the screen reboots. Some features (split screen, for example) flip a system flag that is only applied at boot — without a reboot the feature looks "broken" while everything is actually fine.

**2. Record logs.** Settings → "Service & Data" section → **"Record logs"** button. Reproduce the problem (tap whatever does not work), then tap **"Stop recording"**. A `bydmate_logs_<date>.txt` file appears in `Download` (recording auto-stops after 2 hours if you forget).

**3. Save the fid catalog.** Settings → "Service & Data" section → **"Save fid catalog"** button. A `fid-dump-<date>.txt` file appears in `Download`. Different BYD models have different command catalogs — this file is how we adapt the app to your car.

**4. Send it to us.** Open an issue in [GitHub Issues](https://github.com/AndyShaman/BYDMate/issues) and attach: your car model and DiLink generation, the BYDMate version, what you tapped and what you expected, and the files from steps 2–3.

Common cases:

- **Split screen or cluster projection does not work** — reboot the head unit (step 1).
- **A command runs but the car does not react** (windows, sunroof, climate) — your model most likely has a different command catalog: send logs and the fid catalog (steps 2–4).
- **Something broke after an update** — reboot the head unit; if that does not help, send logs.

---

## Credits

- **[BYD Trip Info](https://www.byd-seal-forum.de/forum/thread/1811-byd-trip-info-app/)** (`org.jayb.bydapp`) by jayb — the original DiLink trip app, inspiration for BYDMate
- **[DiPlus](https://www.dilink.cn/)** (迪加) by Van Design — the bridge app for car data, used in early BYDMate versions (no longer required since 3.0.0)
- **@RBGboost** — reverse engineering of the factory HUD SOME/IP protocol and the original YandexHUD implementation. Yandex Navigator guidance on the HUD in BYDMate is his code and his achievement. You can thank him on Telegram: [@RBGboost](https://t.me/RBGboost)
- **[byd-turnsignal-cameraview](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview)** by sunlixWhyNotAvailable — open research into the DiLink camera and window interfaces that the turn-signal blind-spot camera feature is built on. The BYDMate implementation is written from scratch, but this research saved weeks of reverse engineering — thank you!
- **[danijar2000](https://github.com/danijar2000)** — fix for 2GIS cluster projection (2GIS terminated when its task was moved to the cluster display)

---

## Sponsor the project

The project is non-commercial, built as a hobby. If you want to say thanks, the details are in [SUPPORT.md](SUPPORT.md). If not, thanks anyway for your trust.

---

## License

**PolyForm Noncommercial 1.0.0** — source-available, noncommercial use only.
See [LICENSE](LICENSE) for details.

Copyright (C) 2026 [AndyShaman](https://github.com/AndyShaman)

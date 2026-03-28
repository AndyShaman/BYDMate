# BYDMate

**Trip & Charge Tracker for BYD DiLink 5.0**

Android app for BYD vehicles with DiLink 5.0 head unit (Leopard 3 / Bao 3). Tracks trips, charging sessions, GPS routes, and real energy consumption statistics — all locally, no cloud required.

## Features

- **Real consumption tracking** via delta SOC method (not onboard computer estimates)
- **Trip logging** with GPS route, distance, speed, temperature
- **Charge session tracking** with power curve, AC/DC detection, cost calculation
- **Idle drain tracking** — monitors battery consumption while parked (heating, A/C)
- **BYD history import** from built-in energydata database
- **Interactive map** with trip routes (osmdroid, no Google Maps needed)
- **CSV export** of all trips and charges
- **Multi-currency** support (BYN, RUB, UAH, KZT, USD, EUR, CNY)
- **Auto-update** from GitHub Releases
- **Foreground service** with GPS — works in background, survives reboots

## Screenshots

*Coming soon*

## Target Device

| Parameter | Value |
|-----------|-------|
| Platform | DiLink 5.0 (Android 12, API 32) |
| SoC | Snapdragon 780G |
| Display | 15.6" landscape, 1920x1200 |
| GMS | No (AOSP without Google Play Services) |

## How It Works

BYDMate reads vehicle data from the **DiPlus** (Dilink Plus) app's local API (`localhost:8988`) every 9 seconds:

- Speed, SOC, mileage, battery temperature, charging status
- No OBD needed — uses the same API as BYD's built-in apps
- No cloud/server — everything stays on the head unit

### Data Sources

| Data | Source |
|------|--------|
| Speed, SOC, Power | DiPlus API (getDiPars) |
| GPS coordinates | Android LocationManager |
| Trip history | BYD energydata SQLite |
| Weather/temperature | Open-Meteo API |

## Tech Stack

- Kotlin, Jetpack Compose, Material 3
- Room (SQLite), Hilt (DI), OkHttp
- osmdroid (OpenStreetMap), Coroutines + Flow
- Min SDK 29, Target SDK 32

## Installation

1. Download the latest APK from [Releases](https://github.com/AndyShaman/BYDMate/releases)
2. Transfer to DiLink via USB or network
3. Install (allow unknown sources if prompted)
4. Grant location and storage permissions on first launch

## Building from Source

```bash
# Requirements: JDK 17, Android SDK 34
git clone https://github.com/AndyShaman/BYDMate.git
cd BYDMate
./gradlew assembleDebug
```

## License

**GPLv3** with additional attribution and commercial use terms.
See [LICENSE](LICENSE) for details.

Copyright (C) 2026 [AndyShaman](https://github.com/AndyShaman)

---

*Made for BYD Leopard 3 owners who want to know their real energy consumption.*

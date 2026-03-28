# BYDMate -- Design Document

**Date:** 2026-03-28
**Target:** Android app for BYD DiLink 5.0 (Leopard 3 / 方程豹豹3)
**Platform:** Android 12 (API 32), Snapdragon 780G, 15.6" 2.5K landscape, 12GB RAM
**Package:** `com.bydmate.app`

## 1. Problem

- No TeslaMate/TeslaFi equivalent for BYD vehicles
- BYD onboard computer **underestimates consumption by 10-30%** (ignores HVAC, battery heating, 12V systems)
- No built-in trip history, charging log, or route mapping
- BYD forum users actively asking for these features with no existing solution

## 2. Solution

Native Android app installed on DiLink head unit that:
1. Automatically logs trips with real consumption (delta SOC method)
2. Automatically detects and logs charging sessions (AC/DC)
3. Records GPS routes displayed on map
4. Shows honest statistics with weather correlation

## 3. Data Sources

### 3.1 energydata SQLite (BYD native)
- **Path:** `/storage/emulated/0/energydata`
- **Table:** `EnergyConsumption` (id, start_timestamp, end_timestamp, duration, trip, electricity, is_deleted)
- **Provides:** Trip distance (km), duration, BYD's own kWh estimate
- **Access:** `READ_EXTERNAL_STORAGE` permission, no root needed

### 3.2 getDiPars API (DiPlus, port 8988)
- **Endpoint:** `GET http://localhost:8988/api/getDiPars?text=<template>`
- **Requires:** DiPlus (迪加) app installed (confirmed installed on target vehicle)
- **Poll interval:** 8-10 seconds
- **Request format:** `{parameter_chinese_name}` for numeric, `[parameter_chinese_name]` for text, pipe-separated

**Example request:**
```
GET http://localhost:8988/api/getDiPars?text=SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}|Power:{发动机功率}|ChargeGun:{充电枪插枪状态}|MaxBatTemp:{最高电池温度}|AvgBatTemp:{平均电池温度}|MinBatTemp:{最低电池温度}|ChargingStatus:{充电状态}
```

**Example response:**
```json
{"success": true, "val": "SOC:72|Speed:0|Mileage:342180|Power:0|ChargeGun:0|MaxBatTemp:25|AvgBatTemp:23|MinBatTemp:21|ChargingStatus:0"}
```

Note: Mileage value is in units of 0.1 km (342180 = 34218.0 km). Power is negative during charging (e.g., -7.2 = charging at 7.2 kW).

Key parameters for this app:

| ID | Chinese | Name | Unit | Usage |
|----|---------|------|------|-------|
| 2 | 车速 | Speed | km/h | Trip start/stop detection |
| 3 | 里程 | Mileage (odometer) | km | Trip distance |
| 10 | 发动机功率 | Engine Power | kW | Charge power (negative = charging) |
| 12 | 充电枪插枪状态 | Charge Gun State | binary | Charge detection (2 = connected) |
| 13 | 百公里电耗 | Consumption/100km | kWh | Reference only |
| 14 | 最高电池温度 | Max Battery Temp | C | Battery health |
| 15 | 平均电池温度 | Avg Battery Temp | C | Battery health |
| 16 | 最低电池温度 | Min Battery Temp | C | Battery health |
| 33 | 电量百分比 | Battery SOC | % | Real consumption calc |
| 52 | 充电状态 | Charging Status | -- | Charge state |

### 3.3 Android GPS
- `LocationManager` with `GPS_PROVIDER` + `NETWORK_PROVIDER`
- Foreground service with `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`
- Auto-start on boot via `RECEIVE_BOOT_COMPLETED`

### 3.4 Weather
- **API:** Open-Meteo (free, no API key)
- **Endpoint:** `https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&current_weather=true`
- **Purpose:** Correlate consumption with temperature (cold = +20-45% consumption)

## 4. Core Algorithms

### 4.1 Trip Detection
```
speed > 3 km/h for 10 sec  -->  trip START (record SOC, GPS, timestamp)
speed = 0 for 3 min        -->  trip STOP  (record SOC, GPS, timestamp)
```

### 4.2 Real Consumption Calculation
```
real_kwh = (soc_start - soc_end) / 100 * battery_capacity_kwh
real_kwh_per_100km = real_kwh / distance_km * 100
```
- `battery_capacity_kwh` configured in settings (Leopard 3: 71.8 kWh usable)
- SOC from getDiPars ID 33 (integer %, accuracy ~0.72 kWh per 1%)
- Distance from odometer delta (getDiPars ID 3)

### 4.3 Charge Detection
```
ChargeGunState (ID 12) == 2 AND EnginePower (ID 10) < -1 kW  -->  charging START
ChargeGunState != 2 OR EnginePower >= -1                      -->  charging STOP

EnginePower < -50 kW  -->  DC fast charge
EnginePower >= -50 kW -->  AC charge
```

### 4.4 Charge Energy Calculation
**Primary (power integration):**
```
kwh = SUM(|power_kW| * interval_seconds / 3600)   -- over charging session
```
Polled every 8-10 sec, accuracy ~0.1 kWh.

**Fallback (delta SOC):**
```
kwh = (soc_end - soc_start) / 100 * battery_capacity_kwh
```
Accuracy ~0.7 kWh. Used if app was inactive during part of session.

### 4.5 Cost Calculation
```
cost = kwh_charged * tariff_per_kwh
```
Tariffs configured in settings: home AC rate, public DC rate.

## 5. Screens

### 5.1 Dashboard (Home)
- SOC gauge (large, circular)
- Odometer, real range estimate
- Today's summary: km driven, kWh consumed, kWh/100km
- Last trip card (route name, distance, duration, consumption)
- Last charge card (location, type, SOC change, kWh)

### 5.2 Trips
- Filterable list by week/month
- Period summary: total km, total kWh, avg kWh/100km
- Trip cards grouped by day:
  - Time, distance, kWh, kWh/100km, SOC start->end, temperature
  - Expandable: map route, speed timeline
- Route color-coded by consumption: green (<15), yellow (15-22), red (>22 kWh/100km)

### 5.3 Charges
- Filterable list by period and type (AC/DC/all)
- Period summary: session count, total kWh, total cost
- Charge cards:
  - Time, SOC change, kWh, max power, duration, cost
  - Type icon: house (home AC), lightning (DC)
  - Expandable: power curve over time
- Auto-categorize by location (home geo-fence)

### 5.4 Map
- All routes for selected period on OpenStreetMap (osmdroid)
- Routes colored by consumption
- Charge location markers
- Auto geo-fences: home, work (frequent stop points)

### 5.5 Settings
- Vehicle model + battery capacity (kWh)
- Home tariff (currency/kWh)
- DC tariff (currency/kWh)
- Units (km/miles)
- Data export (CSV)

### Navigation
- Bottom navigation bar: Dashboard | Trips | Charges | Map | Settings
- Single level -- no nested navigation
- Trip/charge details expand in-place

## 6. Android Permissions

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## 7. Tech Stack (see also: Gradle Dependencies below)

| Layer | Technology | Reason |
|-------|-----------|--------|
| UI | Jetpack Compose + Material3 | Modern declarative UI, proven on DiLink (TripInfo uses it) |
| Maps | osmdroid (OpenStreetMap) | Free, offline, no GMS dependency |
| DB | Room (SQLite) | Local storage, offline-first |
| DI | Hilt | Standard Android DI |
| Async | Kotlin Coroutines + Flow | Reactive UI updates |
| HTTP | OkHttp | getDiPars + Open-Meteo requests |
| Language | Kotlin | Android standard |
| Min SDK | 29 (Android 10) | Covers DiLink 5.0 (Android 12) |
| Target SDK | 32 (Android 12) | Match DiLink 5.0 |

## 8. Database Schema (Room)

```sql
CREATE TABLE trips (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    start_ts INTEGER NOT NULL,       -- epoch millis
    end_ts INTEGER,
    distance_km REAL,                -- from odometer delta
    kwh_consumed REAL,               -- delta SOC * capacity
    kwh_per_100km REAL,
    soc_start INTEGER,
    soc_end INTEGER,
    temp_avg_c REAL,                 -- from Open-Meteo
    avg_speed_kmh REAL
);

CREATE TABLE trip_points (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trip_id INTEGER NOT NULL REFERENCES trips(id),
    timestamp INTEGER NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    speed_kmh REAL
);

CREATE TABLE charges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    start_ts INTEGER NOT NULL,
    end_ts INTEGER,
    soc_start INTEGER,
    soc_end INTEGER,
    kwh_charged REAL,                -- power integration (primary)
    kwh_charged_soc REAL,            -- delta SOC (fallback)
    max_power_kw REAL,
    type TEXT,                       -- 'AC' or 'DC'
    cost REAL,
    lat REAL,
    lon REAL
);

CREATE TABLE charge_points (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    charge_id INTEGER NOT NULL REFERENCES charges(id),
    timestamp INTEGER NOT NULL,
    power_kw REAL,
    soc INTEGER
);

CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT
);
```

## 9. Visual Design

- **Theme:** Dark (building from black, per Google Automotive guidelines)
- **Background:** #0D0D0D - #1A1A1A
- **Cards:** #1E1E1E - #2C2C2C, rounded corners
- **Accent:** Green for energy/charge, blue for info, red for warnings
- **SOC colors:** Green (>50%) -> Yellow (20-50%) -> Red (<20%)
- **Consumption colors:** Green (<15) -> Yellow (15-22) -> Red (>22 kWh/100km)
- **Text:** White primary, #9E9E9E secondary
- **Touch targets:** Min 76dp (automotive standard)
- **Typography:** Medium weight, avoid bold, 4dp grid

## 10. App Installation

1. Dial `*#91532547#*` on DiLink screen -> get IMEI
2. Generate ADB password at https://ahmada3mar.github.io/BYD/
3. Enable ADB, install `PackageInstaller.apk` via `adb install`
4. After that: install BYDMate APK from USB stick (FAT32)
5. App auto-starts on boot via BootReceiver

## 11. Distribution

- GitHub Releases (like TripInfo)
- In-app update checker via GitHub API
- APK sideloading via USB

## 12. OBD

**Not needed.** All required data available through energydata SQLite + getDiPars API.
OBD causes alarm triggers on BYD vehicles and breaks after OTA updates.

## 13. Key Gradle Dependencies

```kotlin
// build.gradle.kts (app)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bydmate.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.bydmate.app"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // osmdroid (maps)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
}
```

## 14. Future Ideas (out of scope for MVP)

- Battery degradation tracking (SoH over time)
- Vampire/phantom drain monitoring
- Calendar view (monthly activity overview)
- Data sync to phone/cloud (optional)
- Benchmarking vs other BYD owners
- Widgets for DiLink home screen

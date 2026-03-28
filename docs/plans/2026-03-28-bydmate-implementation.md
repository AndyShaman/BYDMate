# BYDMate -- Implementation Plan

Reference: `docs/plans/2026-03-28-bydmate-design.md`

## Phase 1: Project Skeleton (scaffolding)

### Step 1.1: Create Android project
- Create new Android project with package `com.bydmate.app`
- Set up `build.gradle.kts` with all dependencies from design doc section 13
- Configure `AndroidManifest.xml` with all permissions from section 6
- Verify project compiles with `./gradlew assembleDebug`

### Step 1.2: Set up project structure
```
com.bydmate.app/
  data/
    local/
      dao/          -- Room DAOs (TripDao, ChargeDao, SettingsDao)
      entity/       -- Room entities (TripEntity, TripPointEntity, ChargeEntity, ChargePointEntity)
      database/     -- AppDatabase
    remote/
      DiParsClient.kt      -- getDiPars HTTP client
      WeatherClient.kt     -- Open-Meteo HTTP client
    repository/
      TripRepository.kt
      ChargeRepository.kt
      SettingsRepository.kt
  domain/
    model/          -- Domain models (Trip, Charge, TripPoint, etc.)
    tracker/
      TripTracker.kt       -- Trip detection logic
      ChargeTracker.kt     -- Charge detection logic
    calculator/
      ConsumptionCalculator.kt  -- Real consumption math
  service/
    TrackingService.kt     -- Foreground service (GPS + getDiPars polling)
    BootReceiver.kt        -- Auto-start on boot
  ui/
    theme/          -- Colors, Typography, Theme
    navigation/     -- NavHost, bottom nav
    dashboard/      -- Dashboard screen + ViewModel
    trips/          -- Trips screen + ViewModel
    charges/        -- Charges screen + ViewModel
    map/            -- Map screen + ViewModel
    settings/       -- Settings screen + ViewModel
    components/     -- Shared composables (SocGauge, TripCard, ChargeCard, etc.)
  di/
    AppModule.kt    -- Hilt modules
  BYDMateApp.kt    -- Application class (@HiltAndroidApp)
  MainActivity.kt  -- Single activity
```

### Step 1.3: Room database + entities
- Implement all entities matching schema from design doc section 8 (Database Schema)
- Create DAOs with basic CRUD + queries:
  - `TripDao`: insert, getAll, getByDateRange, getTodaySummary
  - `ChargeDao`: insert, getAll, getByDateRange, getPeriodSummary
  - `TripPointDao`: insertAll, getByTripId
  - `ChargePointDao`: insertAll, getByChargeId
  - `SettingsDao`: get, set
- Create `AppDatabase` with migrations
- **Test:** Verify DB compiles, write basic unit test for DAO

## Phase 2: Data Layer

### Step 2.1: getDiPars client
- Implement `DiParsClient` that:
  - Builds request URL with Chinese parameter names
  - Parses pipe-separated response: `"SOC:72|Speed:0|Mileage:342180"`
  - Returns `DiParsData` data class with typed fields
  - Handles errors gracefully (DiPlus not running, timeout)
- Request template (all params in one call):
  ```
  SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}|Power:{发动机功率}|ChargeGun:{充电枪插枪状态}|MaxBatTemp:{最高电池温度}|AvgBatTemp:{平均电池温度}|MinBatTemp:{最低电池温度}|ChargingStatus:{充电状态}
  ```
- **Test:** Unit test with mocked response

### Step 2.2: Weather client
- Implement `WeatherClient`:
  - `getTemperature(lat, lon) -> Float?`
  - Uses Open-Meteo: `https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&current_weather=true`
  - Parse JSON, extract `current_weather.temperature`
  - Cache result for 15 minutes (don't spam API)
- **Test:** Unit test with mocked response

### Step 2.3: BYD energydata reader
- Implement `EnergyDataReader`:
  - Opens BYD's SQLite at `/storage/emulated/0/energydata`
  - Reads `EnergyConsumption` table: `SELECT * FROM EnergyConsumption WHERE is_deleted = 0 ORDER BY start_timestamp DESC`
  - Returns list of BYD's trip records (for reference/comparison)
  - Read-only, never writes to BYD's DB
- **Note:** This is external SQLite, NOT Room. Use raw `SQLiteDatabase.openDatabase()`.

### Step 2.4: Repositories
- `TripRepository`: combines Room DAO + getDiPars + GPS + Weather
- `ChargeRepository`: combines Room DAO + getDiPars
- `SettingsRepository`: wraps Room SettingsDao

## Phase 3: Core Logic (domain)

### Step 3.1: Trip tracking
- `TripTracker` class:
  - Input: stream of `DiParsData` (speed, SOC, mileage) + GPS locations
  - State machine: IDLE -> DRIVING -> IDLE
  - Start trip: speed > 3 km/h for 10 seconds -> record SOC, mileage, GPS, timestamp
  - End trip: speed = 0 for 3 minutes -> record SOC, mileage, GPS, timestamp, fetch weather
  - Calculate: distance (odometer delta), kWh (delta SOC * capacity), kWh/100km
  - Save trip + trip_points to Room DB

### Step 3.2: Charge tracking
- `ChargeTracker` class:
  - Input: stream of `DiParsData` (ChargeGunState, EnginePower, SOC)
  - State machine: IDLE -> CHARGING -> IDLE
  - Start: ChargeGunState == 2 AND Power < -1
  - During: accumulate kWh via power integration, save charge_points every 30 sec
  - End: ChargeGunState != 2 OR Power >= -1
  - Type: DC if peak power > 50 kW, else AC
  - Cost: kWh * tariff (from settings, AC or DC rate)
  - Save charge + charge_points to Room DB

### Step 3.3: Consumption calculator
- `ConsumptionCalculator`:
  - `realKwh(socStart, socEnd, batteryCapacity) -> Float`
  - `kwhPer100km(kwh, distanceKm) -> Float`
  - `chargePowerIntegration(chargePoints) -> Float` -- sum(|power| * interval/3600)
  - Period statistics: total km, total kWh, avg kWh/100km for date range

## Phase 4: Foreground Service

### Step 4.1: TrackingService
- Android Foreground Service with persistent notification ("BYDMate recording...")
- Every 8-10 seconds:
  1. Call getDiPars (all params in one request)
  2. Read GPS location
  3. Feed data to TripTracker + ChargeTracker
- Uses Kotlin Flow to emit state to UI
- Handles service lifecycle (start/stop/restart)

### Step 4.2: BootReceiver
- `BroadcastReceiver` for `BOOT_COMPLETED`
- Starts `TrackingService` automatically when DiLink boots

## Phase 5: UI

### Step 5.1: Theme + navigation shell
- Dark theme: colors from design doc section 9 (Visual Design)
- Bottom navigation: Dashboard | Trips | Charges | Map | Settings
- NavHost with 5 routes
- Empty placeholder screens

### Step 5.2: Shared components
- `SocGauge` -- circular arc gauge (0-100%, color-coded)
- `TripCard` -- trip summary card (time, distance, kWh, kWh/100km, SOC, temp)
- `ChargeCard` -- charge summary card (time, SOC delta, kWh, power, cost, type icon)
- `SummaryRow` -- period summary (total km | total kWh | avg kWh/100km)
- `SpeedTimeline` -- mini bar chart of speed over trip duration
- `PowerCurve` -- mini chart of charge power over time

### Step 5.3: Dashboard screen
- ViewModel observes: current SOC, odometer, today's trips/charges from Room
- Layout per design doc: SOC gauge, today summary cards, last trip card, last charge card

### Step 5.4: Trips screen
- ViewModel: loads trips from Room, filtered by period (week/month)
- List of TripCards grouped by day, with period SummaryRow
- Expandable: tapping a trip shows map (osmdroid) + speed timeline

### Step 5.5: Charges screen
- ViewModel: loads charges from Room, filtered by period and type
- List of ChargeCards with period SummaryRow (count, kWh, cost)
- Expandable: tapping a charge shows power curve

### Step 5.6: Map screen
- osmdroid MapView wrapped in AndroidView composable
- Load trip_points for selected period, draw polylines
- Color-code by consumption: green/yellow/red
- Charge location markers

### Step 5.7: Settings screen
- Form fields: battery capacity, home tariff, DC tariff, units
- Save to Room settings table
- CSV export button -> write to Downloads, share via intent

## Phase 6: Polish + Distribution

### Step 6.1: Auto-update
- Check GitHub Releases API on app start (once per day)
- Compare version, download APK via DownloadManager, prompt install

### Step 6.2: Testing on device
- Build release APK: `./gradlew assembleRelease`
- Install on DiLink via USB
- Verify: trip detection, charge detection, GPS recording, map rendering

### Step 6.3: GitHub release
- Create GitHub repo
- Tag v0.1.0, attach APK to release

---

## Build Order Summary

```
Phase 1 (skeleton)    -> compiles, empty app with DB
Phase 2 (data)        -> reads getDiPars, weather, energydata
Phase 3 (logic)       -> detects trips/charges, calculates consumption
Phase 4 (service)     -> runs in background, polls data
Phase 5 (UI)          -> all 5 screens with real data
Phase 6 (polish)      -> auto-update, release
```

Each phase should be independently testable. Phase 1-4 can work without UI (verify via logs). Phase 5 connects UI to working backend.

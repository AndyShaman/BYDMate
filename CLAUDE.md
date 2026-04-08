# BYDMate

Android app for BYD DiLink 5.0 head unit (Leopard 3 / 方程豹豹3).
Trip logger + GPS routes + real consumption statistics + AI driving insights.

## Current Version

**v2.1.7** (versionCode=217, DB version=7, targetSdk=29)

## Navigation (3 tabs)

Главная | Поездки | Настройки
*(Charges tab removed in v2.0.7. Map tab removed in v2.0.4 — osmdroid fullscreen crashes DiLink)*

## Architecture (v2.0)

- **Data:** energydata (BYD native SQLite) = primary trip source. DiPlus TripInfo = SOC enrichment. Live tracking = GPS points only (tripId=0, attached later).
- **Sync:** Event-based (service start + app foreground). File change detection via SharedPreferences. No timers.
- **TripTracker:** GPS collector only. Does NOT create TripEntity records (eliminates duplicates).
- **GPS:** requestLocationUpdates with explicit Looper.getMainLooper() + getLastKnownLocation() (2000ms/8m, like TripInfo). Without Looper — GPS silently fails on DiLink.
- **HistoryImporter:** cleanupIdleDrainV2() → syncFromEnergyData() → enrichWithDiPlus() → recalculateConsumptionFromEnergyData() → calculateMissingCosts() → attachGpsPoints(). Also deduplicateWithExisting() for v1.x upgrades. Idle drain = zero-km energydata records only (no live tracking).
- **Autostart (v2.1.0):** BootReceiver → WorkManager → ServiceStartWorker → startForegroundService (like BydConnect). SilentStartActivity removed from boot chain (race condition). USER_PRESENT as backup trigger. Auto-restart on onDestroy/onTaskRemoved. excludeFromRecents on MainActivity. Chain log in SharedPreferences for diagnostics.
- **Consumption data:** energydata `electricity` field (BMS) = authoritative source for kwhConsumed. DiPlus SOC enrichment provides ONLY socStart/socEnd/avgSpeed — does NOT override consumption. SOC delta too coarse (1% = 0.729 kWh) for accurate per-trip consumption.
- **AI Insights (v2.1.3→v2.1.6):** OpenRouter API integration. Once per calendar day, aggregates 7-day + 30-day trip/battery stats, sends to user-selected LLM, caches JSON response. `facts` = deterministic metrics calculated from data (consumption trend, trip count, short trips %, idle drain). `insights` = LLM-generated analysis (correlations, anomalies, advice). Replaces SoH card (which was inaccurate — cannot calculate true SoH without OBD access).
- **Idle drain (v2.1.4):** Live power integration REMOVED — DiPars `发动机功率` is motor power, not total battery drain. Idle drain now calculated solely from energydata zero-km records (BMS). One-time cleanup via `idle_drain_v2_cleanup` flag.
- **Welcome Wizard:** Shown once (setup_completed flag). 2 steps: tariffs → autostart. Di+ command copyable to clipboard.

## SoH — Why It Was Removed (v2.1.3)

SoH card removed because accurate battery health calculation is impossible with available data:
- **Old formula** (`capacity = kwhConsumed / socDelta`) was circular — always returned 100%.
- **After BMS fix** (v2.1.2), formula gave ~90% for NEW battery because energydata `electricity` field does NOT include all battery loads (HVAC, 12V, heating) — only traction energy.
- **BYD uses ~100% of battery as usable** (confirmed from ev-database.org: Seal 82.5/82.56, Atto 60.5/60.48).
- **DiPlus API has no SoH parameter** — checked all 115+ params in decompiled source.
- **True SoH requires OBD2/CAN access** (Car Scanner + ELM327).
- Replaced with AI Insights card — more useful, actually works.

## Changelog (v2.0.4 → v2.1.7)

- **v2.0.7:** Charges tab removed. SoH card added to Dashboard. Russian tab names. Import buttons removed from Settings. UI polish.
- **v2.0.8:** Trips accordion column alignment (fixed-width, right-aligned). Column order unified. Consumption color-coded.
- **v2.1.0:** WorkManager autostart (like BydConnect). excludeFromRecents. Auto-restart on destroy/task-removed.
- **v2.1.1:** Track outline + vivid speed colors on map. Chain log removed from UI.
- **v2.1.2:** One-time consumption recalculation from BMS energydata. enrichWithDiPlus() no longer overwrites kwhConsumed. Flag `consumption_recalc_done`.
- **v2.1.3:** SoH card removed → AI Insights card via OpenRouter. Settings: API key + model picker (full catalog with search). InsightsManager aggregates trip/battery stats, sends to LLM once per day.
- **v2.1.4:** Idle drain fix — removed live power integration (DiPars motor power ≠ total battery drain). One-time cleanup of inflated records. "Save and get insight" button in Settings. Dashboard reloads on tab switch. README rewritten with screenshots.
- **v2.1.5:** Monthly data (30 days) added to AI prompt. AI response split into facts (compact metrics) + insights (detailed analysis). Expanded dialog: facts first, insights below.
- **v2.1.7:** Bar chart panel on Trips screen (right 1/3). Metrics: /100, kWh, cost. Adaptive grouping by period (per-trip/day/month). Tap tooltip. Metric chips. Stops filter auto-switches from /100 to kWh.
- **v2.1.6:** Facts are now deterministic (calculated from data, not LLM). LLM generates only insights. Dialog scroll for long content. Prompt instructs model to NOT repeat metrics.

## Project Docs

- **v2.0 Design:** `docs/plans/2026-03-30-bydmate-v2-design.md` (IMPLEMENTED)
- **v2.0 Implementation:** `docs/plans/2026-03-30-v2-implementation.md` (ALL DONE)
- **v2.0.2 Bugfixes:** `docs/plans/2026-03-30-v2.0.2-bugfixes.md` (ALL DONE)
- **AI Insights Design:** `docs/plans/2026-04-02-ai-insights-design.md` (IMPLEMENTED)

## Key Decisions

- **No OBD** -- causes alarm on BYD, breaks after OTA. All data via DiPlus API + BYD's built-in SQLite.
- **No cloud/server** -- everything local on DiLink head unit (except optional AI insights via OpenRouter).
- **No Google Play Services** -- DiLink runs AOSP without GMS. Use osmdroid for maps, not Google Maps.
- **No fullscreen osmdroid** -- crashes DiLink. Only small map in TripDetailDialog (clipped box).
- **No SoH calculation** -- impossible without OBD. energydata `electricity` ≠ total battery discharge. DiPlus has no SoH param.
- **Real consumption** from energydata BMS `electricity` field, NOT SOC-delta (too coarse for short trips).
- **DiPlus (迪加) app is pre-installed** on target vehicle, provides getDiPars API on localhost:8988.
- **energydata = primary** trip source (most complete). DiPlus TripInfo for SOC enrichment only.
- **Autostart = WorkManager** -- BootReceiver → WorkManager → ServiceStartWorker (like BydConnect). SilentStartActivity had race condition.

## Tech Stack

- Kotlin, Jetpack Compose, Material3, Room 2.6.1, Hilt, OkHttp, osmdroid 6.1.20, Coroutines+Flow
- Min SDK 29, Target SDK 29 (NOT 32! — scoped storage breaks file access on DiLink)
- Package: `com.bydmate.app`

## Target Device

- DiLink 5.0 (Android 12, API 32, but targetSdk=29 for file access)
- Snapdragon 780G, 12GB RAM, 256GB storage
- 15.6" landscape screen, 2.5K resolution (1920x1200 effective)
- No GMS (Google Mobile Services)

## Key Files

- `HistoryImporter.kt` -- sync + enrichment + dedup + consumption recalculation
- `EnergyDataReader.kt` -- reads BYD energydata DB, change detection
- `DiPlusDbReader.kt` -- reads DiPlus TripInfo + ChargingLog
- `OpenRouterClient.kt` -- OpenRouter API: chat completions + model list
- `InsightsManager.kt` -- AI insights: data aggregation, prompt, cache, daily refresh
- `InsightsModels.kt` -- InsightData, OpenRouterModel data classes
- `TripTracker.kt` -- GPS-only collector (no trip creation), needs Looper.getMainLooper()
- `TrackingService.kt` -- triggers sync + AI insights on start, GPS location listener
- `ServiceStartWorker.kt` -- WorkManager worker for autostart
- `BootReceiver.kt` -- autostart on boot via WorkManager
- `DashboardScreen.kt` -- AI insight card, battery health card, idle drain card, period filter, recent trips
- `DashboardViewModel.kt` -- insight loading, trip stats aggregation
- `SettingsScreen.kt` -- OpenRouter API key + model picker dialog
- `TripDetailDialog.kt` -- landscape popup: map left, stats right, speed histogram
- `AppDatabase.kt` -- Room DB version 7
- `AppModule.kt` -- migrations 1→7, Hilt providers

## Reference Databases (for testing sync logic)

- BYD energydata: `~/Downloads/Telegram Desktop/EC_database.db` (57 records, 29 zero-km)
- DiPlus van_bm_db: `~/Downloads/Telegram Desktop/van_bm_db` (+shm, +wal) (54 TripInfo records, 0 ChargingLog)

## Build & Release Workflow

- **НЕ собирать версию, НЕ бампать version, НЕ создавать release** до явного подтверждения пользователя.
- Сначала внести ВСЕ изменения, показать что сделано, дождаться "собирай" / "выпускай" / аналогичного.
- Одна версия = один набор проверенных изменений, а не каждый мелкий фикс.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleRelease
$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
  --ks bydmate-release.jks --ks-key-alias bydmate \
  --ks-pass pass:bydmate123 --key-pass pass:bydmate123 \
  --out BYDMate-vX.Y.Z.apk app/build/outputs/apk/release/BYDMate-vX.Y.Z.apk
gh release create vX.Y.Z BYDMate-vX.Y.Z.apk --title "vX.Y.Z — Title" --notes "..."
```

## Respond in Russian

Always respond to user in Russian. Code comments in English.

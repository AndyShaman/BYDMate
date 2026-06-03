# Phase 1.5 — Shared Adaptive Vehicle Data Loop + Native Trip Recording

**Date:** 2026-05-27
**Branch:** feature/native-data-stack
**Predecessors:** Phase 1b (read migration to native autoservice, v2.8.1), Этап A D+ dead code cleanup
**Successor:** Phase 2 / Этап C — native write channel (separate spec)

## Goal

One shared adaptive read pipeline for the whole app, and native trip recording for vehicles without BYD energydata SQLite (Song, Atto, anything non-Leopard 3).

## Architecture

```
                            ┌──────────────────────────────┐
                            │ SharedAdaptiveLoop (in       │
                            │ TrackingService)             │
                            │                              │
   autoservice Binder  ───► │  NativeParsReader.fetch()    │
   via NativeParsReader     │  + adaptive FSM cadence      │
                            │  + ignition transition detect│
                            │                              │
                            └────────────┬─────────────────┘
                                         │  SharedFlow<DiParsData>
                                         │  replay=1, DROP_OLDEST
                       ┌─────────────────┼─────────────────┬──────────────┐
                       ▼                 ▼                 ▼              ▼
                Dashboard VM    ChargingDetector    TripRecorder    AlicePollingMgr
                                                                         │
                                                                         ▼ (its own
                                                                       ABRP/Iternio
                                                                       VPS POST,
                                                                       ~25s timer)
```

Single coroutine in `TrackingService` owns the `SharedAdaptiveLoop`. All other read consumers (Dashboard, Charging Detector, Trip Recorder, ABRP, Alice device-state reporter) subscribe to its `SharedFlow<DiParsData>`. No second autoservice client exists.

`TripRecorder` writes `TripEntity` records only when `EnergyDataReader.isAvailable() == false`. On Leopard 3 (energydata present), `HistoryImporter.syncFromEnergyData()` remains the trip source as today; `TripRecorder` runs in passive mode, only maintaining `last_state` for cold-start recovery.

## Trip detection — polling FSM (not callback)

Decision: polling FSM based on `powerState` (FID 315621408, dev 1023, transact 5, INT_ENUM) transitions. Backed by codex-rescue + Explore research (2026-05-27):

- D+ does NOT use a `onTripEnd` callback from BYD. Internally it polls via autoservice `transact(9) getBuffer` and broadcasts to its own clients through `RemoteCallbackList<IMsgHander>`. `IMainService.w()` returns a ready `Tripaidl` on demand — pull, not push.
- BYD EV Pro uses periodic polling (`BootstrapPoll.java`, ~5000ms loop) over the same autoservice. Its `pushFidConfig.json` `periodic_whitelist` contains `vehicle_state` FID `315621408` device `1023` — identical to BYDMate's existing `powerState` entry in `FidMap.kt:61`.
- Framework `IBYDAutoListener.onDataChanged()` exists at the SDK level but transact code is not in any decompiled artifact, and the path requires persistent Binder + BYDAUTO signature permissions BYDMate cannot obtain.
- Copying D+ aps_diplus daemon (persistent `app_process --nice-name=aps_diplus` under shell uid, IDDS broadcast handshake, heartbeat) is feasible but costs 6-8 new files / 800-1200 lines / 2-4 weeks. Polling FSM costs ~2-3 files / ~200 lines / 3-5 days. Both validated paths converge on polling.

Trip start = `powerState` transition ACC → ON. Trip end = ON → ACC or ON → OFF. Cadence in driving state is 1 second, matching what D+ and EV Pro do internally.

## FSM cadence

| State | Trigger | Tick interval |
|-------|---------|---------------|
| drive | ignition=ON and speed>0 | 1s |
| charge | chargeGunState=connected | 5s |
| parked | ignition=ON and speed=0 | 5s |
| idle | ignition=ACC or OFF | 30s |

Cadence changes apply on the next tick after a state transition, with no reset of timers.

## Components

### New files

- **`app/src/main/kotlin/com/bydmate/app/data/loop/SharedAdaptiveLoop.kt`**
  Owns `SharedFlow<DiParsData>` (replay=1, BufferOverflow.DROP_OLDEST). Runs a single coroutine driven by `NativeParsReader.fetch()`. Computes FSM state from each tick, picks next delay accordingly. Emits the tick to the flow on success; emits nothing on null and updates the connectivity StateFlow. Updates `last_state` row each tick.

- **`app/src/main/kotlin/com/bydmate/app/data/trips/TripRecorder.kt`**
  Subscribes to `SharedAdaptiveLoop.flow`. Watches `powerState` transitions. Writes `TripEntity` rows with `source = NATIVE_POLLING` when `EnergyDataReader.isAvailable() == false`. On Leopard 3, passive: maintains `last_state.openTripId = null`, writes no native rows.

- **`app/src/main/kotlin/com/bydmate/app/data/local/entity/LastStateEntity.kt`** + **`app/src/main/kotlin/com/bydmate/app/data/local/dao/LastStateDao.kt`**
  Single-row table `last_state` (`id` always 1). Fields: `soc INTEGER?`, `mileage REAL?`, `ts INTEGER`, `ignition INTEGER?`, `open_trip_id INTEGER?`, `trip_start_ts INTEGER?`, `trip_start_soc INTEGER?`, `trip_start_mileage REAL?`, `energydata_available INTEGER`. Atomic write each tick. **Persistent — distinct from existing in-memory `LastSessionRepository`**, which keeps SOC bookmarks for `HistoryImporter` SOC enrichment and stays in-memory by design.

### Modified files

- **`TrackingService.kt`** — hosts `SharedAdaptiveLoop` via Hilt injection. Existing polling job replaced by `loop.start(scope)`. `vehicleDataConnected` StateFlow stays, moves under the loop.
- **`EnergyDataReader.kt`** — adds `fun isAvailable(): Boolean` returning whether `/storage/emulated/0/energydata` contains a readable BYD trips DB. Trivial: directory + file probe, no schema validation needed.
- **`DashboardViewModel.kt`** — collects `sharedAdaptiveLoop.flow` instead of polling. No call site change for UI consumers.
- **`AutoserviceChargingDetector.kt`** — collects `sharedAdaptiveLoop.flow` instead of its own ParsReader call.
- **`SettingsViewModel.kt#runDiagnostics`** — takes one tick from `sharedAdaptiveLoop.flow.first()` for the live snapshot.
- **`IternioClient`** / ABRP policy — subscribes to `sharedAdaptiveLoop.flow`, downstream throttle to `IternioIntervalPolicy.DRIVING_MS / PARKED_MS`. Its own polling loop is removed.
- **`AlicePollingManager.kt`** — replaces internal autoservice reads with `sharedAdaptiveLoop.flow.replayCache.firstOrNull()`. Its own VPS POST timer (~25s) stays unchanged.
- **`HistoryImporter.kt`** — `syncFromEnergyData()` already tags inserted rows with `source = "energydata"`. Dedup considers `source` so native rows and energydata rows can coexist without false-positive duplicates.
- **`AppDatabase.kt`** — version 13 → 14. Migration adds the `last_state` table only (additive, no destructive changes). `TripEntity.source` column already exists since migration 5→6.
- **`TripEntity`** — no schema change. Existing `source: String` column (defaultValue `"live"`) is reused. New value `"native_polling"` joins existing `"live"` and `"energydata"`. `kwhConsumed` remains nullable; native fills it from `socDelta × batteryCapacityKwh` (approximate, BMS-grade accuracy not available without energydata).
- **`TripSource.kt`** (new) — `object TripSource { const val LIVE = "live"; const val ENERGYDATA = "energydata"; const val NATIVE_POLLING = "native_polling" }`. Replaces magic strings in `HistoryImporter` and `TripRecorder`.

## Data flow scenarios

**Drive (non-Leopard 3):**
- First tick `powerState=ON` after `ACC` (or no openTrip in last_state): `TripRecorder` opens trip — captures `trip_start_ts`, `trip_start_soc`, `trip_start_mileage`, assigns provisional `open_trip_id` in `last_state`.
- Subsequent ticks: `last_state` row updates `{soc, mileage, ts}`. No row written to `trips` table yet.
- Transition `ON → ACC` observed in a tick: `TripRecorder` inserts `TripEntity{source="native_polling", startTs=last_state.trip_start_ts, endTs=tick.ts, socStart=last_state.trip_start_soc, socEnd=tick.soc, mileageStart=last_state.trip_start_mileage, mileageEnd=tick.mileage, kwhConsumed=(socStart-socEnd)/100 × batteryCapacityKwh, ...}`. Clears `open_trip_id`.

**Cold start:**
- `TrackingService.onStartCommand` loads `last_state` before starting the loop.
- First `flow` tick: if `last_state.open_trip_id != null`:
  - `now - last_state.ts < 5 min` → resume (keep open_trip_id, continue ticking)
  - `now - last_state.ts >= 5 min` → finalize stale trip from last_state (best-effort end), clear open_trip_id, optionally open a new trip if `powerState=ON` now.
- If `last_state` is missing or corrupted → start fresh.

**Hard shutdown (DiLink screen powered down mid-tick):**
- Last `last_state` write holds the most recent snapshot.
- On next cold start, the gap test above closes the stale trip on the recovered snapshot.

**Leopard 3 (energydata present):**
- `TripRecorder` runs in passive mode: still updates `last_state`, but never inserts to `trips`.
- `HistoryImporter.syncFromEnergyData()` continues as today, already marking inserted rows `source="energydata"`.

**Subscriber concurrency:**
- `SharedFlow(replay=1, BufferOverflow.DROP_OLDEST)` lets slow consumers (Alice VPS POST) drop intermediate ticks without blocking the loop.
- Dashboard/Charging Detector subscribe with default (no throttle).

## Error handling

- **autoservice fetch failure (null result):** `consecutiveNullCount++`, exponential backoff cadence × 1.5 up to `MAX_POLL_INTERVAL_MS = 60_000`. On reconnect, reset cadence and emit `vehicleDataConnected = true`. No emission to `SharedFlow` on null; replayCache keeps the last valid tick.
- **`powerState` sentinel/null streak ≥ 5 ticks:** `TripRecorder` falls back to derived ignition `(gear != null || speed > 0)`. One-shot WARN log. The fallback uses `gear` FID `555745336` (already validated). Smoke flags it for the next release.
- **Trip insert failure (Room exception):** caught; `open_trip_id` stays set in `last_state`; retried via the same reconciliation path on the next tick.
- **Corrupt or missing `last_state` row on cold start:** fresh start. No retroactive trip. WARN log.
- **energydata availability change:** `EnergyDataReader.isAvailable()` re-checked at every `Service` boot; cached in `last_state.energydata_available`. Active/passive mode of `TripRecorder` decided from this flag.

## Testing

**Unit (JVM, no Android dependency):**
- `SharedAdaptiveLoopTest`: FSM transitions drive↔parked↔idle↔charge; cadence numbers correct after each transition.
- `TripRecorderTest`: ACC→ON opens a single trip, ON→ACC closes with the correct snapshot, repeated ON does not double-open.
- `ColdStartReconciliationTest`: gap<5min resumes, gap≥5min finalizes stale + opens new, corrupt state fresh-starts.
- `PowerStateFallbackTest`: 5 consecutive null/sentinel `powerState` ticks → fallback to derived-from-gear ignition.

**Integration (Robolectric):**
- `LastStateDaoTest`: atomic single-row read/write.
- `Migration13To14Test`: real v13 DB dump in `src/androidTest/assets/` → migration applied → `last_state` exists; existing `trips` rows are untouched (`source` already populated from migrations 5→6 onward).
- `SharedFlowConcurrencyTest`: five subscribers, one deliberately slow; loop is never blocked; replayCache delivers last value to late subscribers.

**Smoke on real Leopard 3:**
- One round trip → `TripEntity` row with `source = "energydata"` (existing path, unchanged).
- Service force-stop mid-drive + restart → `last_state` has open trip, resume yields one row, no duplicates.
- DiLink reboot then drive → cold start closes the stale trip from `last_state`, new trip starts.
- Charging session → `TripRecorder` writes nothing; FSM enters `charge` state, dashboard still updates at 5s.
- Dashboard visual refresh at 1s in drive.
- ABRP timeline receives data at its policy cadence, no second autoservice client.
- Alice VPS POST every ~25s carries fresh state from the shared loop replayCache.

**Non-Leopard 3 validation:**
- No internal tester. `TripRecorder` native path goes to production directly on release. Existing Leopard 3 users keep their energydata path untouched. Bugs surface from real Song/Atto users post-release; spec accepts this risk.

## Out of scope

- Native write channel (D+ command bus replacement). This stays HTTP `localhost:8988/api/sendCmd` and is the subject of Phase 2 / Этап C.
- Callback-based trip detection (`IBYDAutoListener.onDataChanged()`, aps_diplus-style daemon). Documented as deferred; the cost/value ratio does not justify it given that polling matches both D+ and EV Pro behaviour.
- ABRP `IternioIntervalPolicy` redesign — keep current downstream throttle.
- Smart Home / Alice protocol — unchanged, just swapped to consume the shared flow.

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| `powerState` FID returns sentinel on some `powerState` transitions not covered by S9 sweep | Fallback to derived ignition `(gear or speed>0)` after 5 null ticks. Smoke logs flag and we pull the FID if it misbehaves. |
| Native trip recording produces wrong consumption on Song/Atto (SOC delta × capacity is coarse) | Document approximation in user-facing strings; let manual edit override. Long-term: explore CAN/OBD when feasible. |
| Cold start lag on DiLink (~3-5 s service startup) causes 50-100m distance loss at trip start | Acceptable. Reconciliation from `last_state` recovers SOC/odometer if the next tick lands during a still-open trip. |
| Concurrent writers to `last_state` (loop + cold-start finalizer) | Cold start finalizer runs before loop starts; loop is the only writer afterward. Serialized by the single coroutine. |
| Migration v13→v14 fails on some user devices | Test with real v13 DB dumps. Migration is additive (only adds new `last_state` table; no changes to existing `trips` columns) so rollback is data-safe. |

## Implementation order (writing-plans seed)

1. Room migration v13→v14: `last_state` table only (no `TripEntity` schema change — `source` column already exists).
2. `LastStateEntity` + `LastStateDao` + DI binding. `TripSource.kt` constants.
3. `SharedAdaptiveLoop` (FSM, SharedFlow, snapshot persistence).
4. `TrackingService` swap: existing poll loop → `SharedAdaptiveLoop`.
5. Consumer swaps: Dashboard, Charging Detector, Settings Diagnostics, ABRP, Alice. One commit per.
6. `TripRecorder` with passive/active modes + reconciliation on cold start.
7. Unit + integration tests.
8. Smoke on real Leopard 3.

Detailed task breakdown will be produced by the writing-plans skill after this spec is approved.

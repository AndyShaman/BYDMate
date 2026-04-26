# BYDMate v2.4.16 (autoservice-readonly Commit 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Завершить read-only autoservice ветку — backend safety/diagnostics, UI long-press редактирование зарядок, малая 3-значная карточка батареи + развёрнутый BatteryHealth диалог. Релиз v2.4.16.

**Architecture:** Surgical edits на существующих компонентах. Один источник правды для live battery state — `BatteryStateRepository`. Удаляем полноэкранный `BatteryHealthScreen` route, заменяем на dialog. ChargeRow получает long-press handler, ChargeEditDialog — новый компонент (поля type/SOC/kWh/tariff_per_kwh).

**Tech Stack:** Kotlin, Jetpack Compose Material3 (`combinedClickable`, `ModalBottomSheet`, `Dialog`), Room (`@Delete`, `@Query DELETE`), Hilt, Coroutines/Flow.

**Reference mocks:**
- `docs/superpowers/mocks/2026-04-26-battery-dialog.html` — малая карточка вариант C + dialog
- `docs/superpowers/mocks/2026-04-26-charge-edit.html` — long-press → bottom sheet → edit dialog

**Current state on entry:**
- Branch `feature/autoservice-readonly` HEAD `f1e3a80` (off main `850d8d8`).
- Working tree clean. Тесты зелёные (51 unit, build SUCCESSFUL release).
- `versionCode = 255`, `versionName = "2.4.15"` в `app/build.gradle.kts`.
- На DiLink стоит v2.4.15 (smoke выявил что коммит 1 уже работает: SoH=100% читается через прямой adb shell).

---

## File Structure

### Commit 2.1 — backend/data
- Modify: `app/src/main/kotlin/com/bydmate/app/data/local/dao/ChargeDao.kt` (+`deleteEmpty()`, +`@Delete delete(charge)`)
- Modify: `app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt` (safety floor + outcome logs)
- Modify: `app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt` (3-fid isAvailable + sentinel logs)
- Modify: `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt` (+`chargeDao.deleteEmpty()` after sync, +log in catchUp)
- Modify: `app/src/main/kotlin/com/bydmate/app/data/repository/ChargeRepository.kt` (+`deleteEmpty()`, +`delete(charge)` passthroughs)
- Test: `app/src/test/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetectorTest.kt` (+regression)
- Test: `app/src/test/kotlin/com/bydmate/app/data/autoservice/AutoserviceClientImplTest.kt` (+isAvailable 3-fid test)

### Commit 2.2 — UI
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsScreen.kt` (line 835 — убрать Wi-Fi detail)
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/charges/ChargesScreen.kt` (`combinedClickable` long-press на `ChargeRow`, ModalBottomSheet)
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/charges/ChargesViewModel.kt` (action-sheet state + edit/delete handlers)
- Create: `app/src/main/kotlin/com/bydmate/app/ui/charges/ChargeEditDialog.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt` (state расширить SoH/lifetime, переименовать flag→fullState)
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardScreen.kt` (новая `BatteryCompactCard` 3-кол., tap → dialog)
- Create: `app/src/main/kotlin/com/bydmate/app/ui/battery/BatteryHealthDialog.kt` (заменяет полноэкранный screen)
- Delete: `app/src/main/kotlin/com/bydmate/app/ui/battery/BatteryHealthScreen.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/navigation/AppNavigation.kt` (удалить `battery_health` route и `onNavigateBatteryHealth`)
- Test: `app/src/test/kotlin/com/bydmate/app/ui/charges/ChargesViewModelTest.kt` (+edit/delete handlers test)

### Commit 2.3 — release
- Modify: `app/build.gradle.kts` (versionCode 256, versionName "2.4.16")
- Build: `BYDMate-v2.4.16.apk` (release, signed)
- Push to DiLink, smoke test, codex audit, GitHub release.

---

## Commit 2.1 — backend/data

### Task 1: `ChargeDao.deleteEmpty()` + Repository passthrough

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/local/dao/ChargeDao.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/data/repository/ChargeRepository.kt`

- [ ] **Step 1: Add `deleteEmpty()` query to `ChargeDao`**

В файл `ChargeDao.kt` после `hasLegacyCharges()` (строка 76):

```kotlin
    /**
     * Removes empty/junk charge rows that runCatchUp left behind in v2.4.15
     * (3-row morning batch). Triggered once per TrackingService.onCreate after
     * historyImporter.runSync. Filter mirrors the safety floor in
     * AutoserviceChargingDetector (delta < 0.05 kWh ≈ measurement noise).
     */
    @Query("DELETE FROM charges WHERE kwh_charged IS NULL OR kwh_charged < 0.05")
    suspend fun deleteEmpty(): Int

    @androidx.room.Delete
    suspend fun delete(charge: ChargeEntity)
```

- [ ] **Step 2: Add Repository passthroughs**

В `ChargeRepository.kt` после `hasLegacyCharges()` (строка 69):

```kotlin
    suspend fun deleteEmpty(): Int = chargeDao.deleteEmpty()

    suspend fun deleteCharge(charge: ChargeEntity) = chargeDao.delete(charge)
```

- [ ] **Step 3: Build to verify Room compiles the new queries**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`. Room kapt accepts the new `@Query DELETE` and `@Delete`.

- [ ] **Step 4: Update existing test fakes — add deleteEmpty/delete overrides**

В `app/src/test/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetectorTest.kt` `RecordingDao` (строка 38), добавить после `hasLegacyCharges` (строка 64):

```kotlin
        override suspend fun deleteEmpty(): Int = inserted.removeAll {
            it.kwhCharged == null || (it.kwhCharged ?: 0.0) < 0.05
        }.let { 0 } // we don't return real count in tests
        override suspend fun delete(charge: ChargeEntity) {
            inserted.removeAll { it.id == charge.id }
        }
```

В `app/src/test/kotlin/com/bydmate/app/ui/charges/ChargesViewModelTest.kt` `FakeChargeDao` нужно те же два override после `getPeriodSummary` — найти секцию и добавить аналогично (тот же snippet, без поля `inserted` — вместо него `chargesFlow.value`). Откорректировать сигнатуры под уже существующий FakeChargeDao.

- [ ] **Step 5: Run unit tests to verify no fakes are broken**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, 51+ tests passing (no new tests yet — just compile-level integration).

---

### Task 2: Safety floor + outcome logs in `AutoserviceChargingDetector`

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt`
- Test: `app/src/test/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetectorTest.kt`

- [ ] **Step 1: Write the failing regression test for safety floor**

В конец `AutoserviceChargingDetectorTest.kt` (перед закрывающей скобкой класса):

```kotlin
    @Test
    fun `delta below safety floor 005 returns NO_DELTA without inserting`() = runTest {
        // 600.02 - 600.00 = 0.02 kWh, below the 0.05 safety floor.
        // BMS calibration drift / measurement noise should never create a session.
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 600.02f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(battery, baselineKwh = 600.0)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.NO_DELTA, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.AutoserviceChargingDetectorTest.delta below safety floor 005 returns NO_DELTA without inserting" 2>&1 | tail -20`

Expected: FAIL — поскольку текущий код проверяет только `delta < MIN_DELTA_KWH = 0.5`, для delta=0.02 он пройдёт дальше и попытается создать ChargeEntity. Тест увидит `SESSION_CREATED` или ошибку (gunState=1 → heuristic → AC). Нужно зафиксировать что тест **fails** до изменения, потом пройдёт после.

- [ ] **Step 3: Add safety floor + diag logs in `runCatchUp`**

В `AutoserviceChargingDetector.kt`:

После `companion object` блока добавить TAG (строка 62, перед `private val mutex`):

```kotlin
    private companion object {
        const val TAG = "AutoserviceDetector"
        // Safety floor below MIN_DELTA_KWH — protects against BMS calibration drift
        // (sub-100Wh wobble in lifetime_kwh after a full charge → wrong-positive
        // session row with kwhCharged ≈ 0). Empty rows are also DB-cleaned at
        // service start, but the floor avoids the flicker entirely.
        const val SAFETY_FLOOR_KWH = 0.05
    }
```

NB: в файле уже есть `companion object` (строка 52). Расширить его, не создавать второй. Финальный вид:

```kotlin
    companion object {
        const val MIN_DELTA_KWH = 0.5
        const val HEURISTIC_HOURS = 1.0
        const val MIN_SOC_DELTA_FOR_SNAPSHOT = 5
        const val SAFETY_FLOOR_KWH = 0.05
        private const val GUN_STATE_NONE = 1
        private const val TAG = "AutoserviceDetector"
    }
```

В `runCatchUp` метод, **после** `val delta = lifetimeKwh - baseline` (строка 92), **перед** существующей проверкой `if (delta < MIN_DELTA_KWH)`, добавить safety-floor:

```kotlin
            if (delta < SAFETY_FLOOR_KWH) {
                android.util.Log.i(TAG, "runCatchUp: delta=${"%.3f".format(delta)} kWh < safety floor $SAFETY_FLOOR_KWH → NO_DELTA")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.NO_DELTA, deltaKwh = delta)
            }
```

И заменить существующий лог в `if (delta < MIN_DELTA_KWH)` блоке — добавить Log.i:

```kotlin
            if (delta < MIN_DELTA_KWH) {
                android.util.Log.i(TAG, "runCatchUp: lifetime=${"%.3f".format(lifetimeKwh)}, baseline=${"%.3f".format(baseline)}, delta=${"%.3f".format(delta)} → below MIN_DELTA_KWH=$MIN_DELTA_KWH")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.NO_DELTA, deltaKwh = delta)
            }
```

Также добавить Log.i для остальных outcomes — в `BASELINE_INITIALIZED` (после `baselineStore.setBaseline(...)` строка 87):

```kotlin
                android.util.Log.i(TAG, "runCatchUp: cold start, baseline=${"%.3f".format(lifetimeKwh)} kWh")
```

И для `SESSION_CREATED` (перед `return CatchUpResult(SESSION_CREATED, ...)` строка 149):

```kotlin
            android.util.Log.i(TAG, "runCatchUp: SESSION_CREATED id=$chargeId, lifetime=${"%.3f".format(lifetimeKwh)}, baseline=${"%.3f".format(baseline)}, delta=${"%.3f".format(delta)}, type=$type, socStart=$socStart, socEnd=$socEnd")
```

И для `SENTINEL` (перед `return CatchUpResult(SENTINEL)` строка 82):

```kotlin
                android.util.Log.i(TAG, "runCatchUp: lifetimeKwh sentinel — BMS not initialized")
```

И для `AUTOSERVICE_UNAVAILABLE` (перед `return CatchUpResult(AUTOSERVICE_UNAVAILABLE)` строка 76):

```kotlin
                android.util.Log.i(TAG, "runCatchUp: autoservice client not available")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.AutoserviceChargingDetectorTest" 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`, все тесты класса PASS включая новый `delta below safety floor 005 returns NO_DELTA without inserting`.

---

### Task 3: Sentinel logs in `AutoserviceClient` getInt/getFloat

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt`

- [ ] **Step 1: Add post-decoder Log.w when SentinelDecoder filters out a real value**

В `AutoserviceClient.kt`:

В `getInt` метод (строки 46-53) — заменить целиком:

```kotlin
    override suspend fun getInt(dev: Int, fid: Int): Int? {
        val cmd = "service call autoservice ${FidRegistry.TX_GET_INT} i32 $dev i32 $fid"
        val raw = adb.exec(cmd)
        if (raw == null) { Log.w(TAG, "getInt($dev,$fid): exec null"); return null }
        val value = parseParcelInt(raw)
        if (value == null) { Log.w(TAG, "getInt($dev,$fid): parse failed: ${raw.take(160)}"); return null }
        val decoded = SentinelDecoder.decodeInt(value)
        if (decoded == null) {
            Log.w(TAG, "getInt($dev,$fid): sentinel raw=0x${"%08x".format(value)} (${value})")
        }
        return decoded
    }
```

В `getFloat` метод (строки 55-62) — заменить целиком:

```kotlin
    override suspend fun getFloat(dev: Int, fid: Int): Float? {
        val cmd = "service call autoservice ${FidRegistry.TX_GET_FLOAT} i32 $dev i32 $fid"
        val raw = adb.exec(cmd)
        if (raw == null) { Log.w(TAG, "getFloat($dev,$fid): exec null"); return null }
        val bits = parseParcelInt(raw)
        if (bits == null) { Log.w(TAG, "getFloat($dev,$fid): parse failed: ${raw.take(160)}"); return null }
        val decoded = SentinelDecoder.parseFloatFromShellInt(bits)
        if (decoded == null) {
            Log.w(TAG, "getFloat($dev,$fid): sentinel bits=0x${"%08x".format(bits)} (raw float=${java.lang.Float.intBitsToFloat(bits)})")
        }
        return decoded
    }
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run existing autoservice tests to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.*" 2>&1 | tail -20`
Expected: all PASS. Sentinel logs are runtime-only — `Log.w` no-ops in unit tests (Robolectric not loaded, but `android.util.Log` calls are harmless if Mockito doesn't intercept).

NB: если тесты класса `AutoserviceClientImplTest` падают из-за вызова `Log.w` — добавить в начало test-класса:

```kotlin
@org.junit.Before
fun stubLog() {
    org.mockito.Mockito.mockStatic(android.util.Log::class.java)
}
```

Если уже есть подобный setup — пропустить.

---

### Task 4: `isAvailable()` через 3 fid'а

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt`
- Test: `app/src/test/kotlin/com/bydmate/app/data/autoservice/AutoserviceClientImplTest.kt`

- [ ] **Step 1: Write the failing test (first SoH probe returns sentinel, but lifetime_kwh works)**

Открыть `AutoserviceClientImplTest.kt`, посмотреть текущую структуру (есть FakeAdbOnDeviceClient, getCommandResponse-pattern). Добавить тест:

```kotlin
    @Test
    fun `isAvailable returns true when SoH is sentinel but lifetime_kwh works`() = runTest {
        // Simulates BMS-calibrating-after-full-charge: SoH probe returns -1.0f sentinel
        // for ~30 sec but lifetime_kwh stays valid. Without 3-fid fallback the user
        // would see "ADB не отвечает" while catch-up still works.
        val fakeAdb = FakeAdbOnDeviceClient(connectedFlag = true).apply {
            // SoH int returns 0xFFFFD8E5 = WRONG_DIRECTION sentinel → null
            setResponse("service call autoservice 5 i32 1014 i32 1145045032",
                "Result: Parcel(00000000 ffffd8e5   '....[...')")
            // lifetime_kwh float returns 0x44197A66 ≈ 612.6f
            setResponse("service call autoservice 7 i32 1014 i32 1032871984",
                "Result: Parcel(00000000 44197a66   '....[...')")
        }
        val client = AutoserviceClientImpl(fakeAdb)

        assertTrue(client.isAvailable())
    }

    @Test
    fun `isAvailable returns false when all 3 probe fids return sentinel`() = runTest {
        val fakeAdb = FakeAdbOnDeviceClient(connectedFlag = true).apply {
            // All three return WRONG_DIRECTION
            setResponse("service call autoservice 5 i32 1014 i32 1145045032",
                "Result: Parcel(00000000 ffffd8e5   '....[...')")
            setResponse("service call autoservice 7 i32 1014 i32 1032871984",
                "Result: Parcel(00000000 ffffd8e5   '....[...')")
            setResponse("service call autoservice 7 i32 1014 i32 1246777400",
                "Result: Parcel(00000000 ffffd8e5   '....[...')")
        }
        val client = AutoserviceClientImpl(fakeAdb)

        assertFalse(client.isAvailable())
    }
```

NB: имена методов и сигнатуры `FakeAdbOnDeviceClient` могут отличаться — открыть существующий тест-файл и адаптировать к фактическому API. Если в файле уже есть фейк с другим именем — использовать его.

- [ ] **Step 2: Run test to verify it fails (current isAvailable probes only SoH)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.AutoserviceClientImplTest.isAvailable returns true when SoH is sentinel but lifetime_kwh works" 2>&1 | tail -20`
Expected: FAIL — текущий код возвращает false как только SoH=null.

- [ ] **Step 3: Modify `isAvailable()` to probe 3 fids in fallback chain**

В `AutoserviceClient.kt` заменить `isAvailable()` целиком (строки 28-44):

```kotlin
    override suspend fun isAvailable(): Boolean {
        // Lazy reconnect: protocol singleton lives in process memory only.
        if (!adb.isConnected()) {
            val r = adb.connect()
            if (r.isFailure) {
                Log.w(TAG, "isAvailable: connect failed: ${r.exceptionOrNull()?.message}")
                return false
            }
        }
        // Probe in fallback order: SoH → lifetime_kwh → SOC. Any one non-null
        // means autoservice is responding. SoH alone is fragile during BMS
        // recalibration after a full charge (can return -1.0f sentinel for
        // tens of seconds while the rest of the bus is fine).
        getInt(FidRegistry.DEV_STATISTIC, FidRegistry.FID_SOH)?.let { return true }
        getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_LIFETIME_KWH)?.let { return true }
        getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_SOC)?.let { return true }
        Log.w(TAG, "isAvailable: all 3 probe fids returned sentinel")
        return false
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.*" 2>&1 | tail -20`
Expected: all PASS, включая оба новых теста.

---

### Task 5: TrackingService integration — call `deleteEmpty()` on service start

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`

- [ ] **Step 1: Inject `chargeDao` через chargeRepository.deleteEmpty()**

`TrackingService` уже инжектит `chargeRepository` (строка 55). Используем passthrough из Task 1.

В `TrackingService.kt` найти блок `serviceScope.launch { try { val result = historyImporter.runSync() ... }` (строка 199). Сразу после `historyImporter.runSync()` (строка 201) и перед `Log.i(TAG, "Sync: ${result.details ...}")` добавить:

```kotlin
                // v2.4.16: одноразово вычищаем "пустые" зарядки, оставшиеся от
                // detector-багов v2.4.15 (catch-up при неправильных tx-кодах писал
                // ChargeEntity с большинством полей null). Защита `if (delta<0.05)` в
                // детекторе предотвращает повторение, но историю надо подмести.
                try {
                    val deleted = chargeRepository.deleteEmpty()
                    if (deleted > 0) Log.i(TAG, "Cleaned $deleted empty charge row(s)")
                } catch (e: Exception) {
                    Log.w(TAG, "deleteEmpty failed: ${e.message}")
                }
```

NB: `Int` возврат от Room DELETE на самом деле количество удалённых строк (Room специфика — `@Query` `DELETE` с `suspend fun ...: Int`). Это работает на практике даже если в Room документации не подсвечено.

- [ ] **Step 2: Run unit tests + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit 2.1**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/local/dao/ChargeDao.kt \
        app/src/main/kotlin/com/bydmate/app/data/repository/ChargeRepository.kt \
        app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt \
        app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt \
        app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt \
        app/src/test/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetectorTest.kt \
        app/src/test/kotlin/com/bydmate/app/data/autoservice/AutoserviceClientImplTest.kt \
        app/src/test/kotlin/com/bydmate/app/ui/charges/ChargesViewModelTest.kt

git commit -m "$(cat <<'EOF'
feat(autoservice): backend hardening for v2.4.16

- ChargeDao.deleteEmpty() removes empty/junk rows on service start
  (cleans up runCatchUp leftovers from v2.4.15 tx-code bug)
- ChargeDao.delete(charge) for upcoming long-press delete UI
- AutoserviceChargingDetector: 0.05 kWh safety floor under MIN_DELTA_KWH
  prevents BMS calibration drift from creating zero-kwh sessions
- AutoserviceChargingDetector: Log.i in every runCatchUp outcome for
  field diagnostics (lifetime/baseline/delta/socStart/socEnd/chargeId)
- AutoserviceClient: sentinel Log.w in getInt/getFloat (was silent before)
- AutoserviceClient.isAvailable() probes SoH → lifetime_kwh → SOC chain;
  survives BMS recalibration after a full charge

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Commit 2.2 — UI

### Task 6: Убрать Wi-Fi подсказку из SettingsScreen

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Replace Disconnected detail copy**

В файле `SettingsScreen.kt` найти `AutoserviceStatusBlock` (строка ~828), case `Disconnected` (строка 831-837):

Было:
```kotlin
        AutoserviceStatus.Disconnected -> StatusRow(
            marker = "✗",
            markerColor = TextMuted,
            title = "не подключено",
            detail = "проверь что Wi-Fi на DiLink включён",
            detailColor = TextSecondary,
        )
```

Заменить на:
```kotlin
        AutoserviceStatus.Disconnected -> StatusRow(
            marker = "✗",
            markerColor = TextMuted,
            title = "не подключено",
            detail = "перезапусти приложение, если ADB включён в Настройках разработчика",
            detailColor = TextSecondary,
        )
```

NB: текст-замена обоснован — Wi-Fi реально не нужен (loopback ADB), но текст нужен какой-то. Если у Andy будут возражения по тексту — заменить на пустую строку `""` и убрать вторую `Text` в `StatusRow` через условный рендеринг. Пока — короткий полезный fallback hint.

- [ ] **Step 2: Build to verify**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

---

### Task 7: ChargeEditDialog (новый файл)

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/ui/charges/ChargeEditDialog.kt`

- [ ] **Step 1: Создать новый файл с composable**

Полный файл `ChargeEditDialog.kt`:

```kotlin
package com.bydmate.app.ui.charges

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.ui.theme.*

/**
 * Edit dialog for a charge row (long-press → bottom sheet → "Изменить").
 *
 * Editable fields: type (AC/DC), socStart, socEnd, kwhCharged, tariff per kWh.
 * On save: cost = kwh * tariff; if socEnd > socStart, kwhChargedSoc is recomputed
 * with the configured battery capacity (passed in via `batteryCapacityKwh`).
 *
 * Tariff input is per-kWh, NOT total cost (per spec).
 */
@Composable
fun ChargeEditDialog(
    charge: ChargeEntity,
    homeTariff: Double,
    dcTariff: Double,
    batteryCapacityKwh: Double,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onSave: (ChargeEntity) -> Unit,
) {
    var type by remember(charge.id) { mutableStateOf(charge.type ?: "AC") }
    var socStartText by remember(charge.id) { mutableStateOf(charge.socStart?.toString() ?: "") }
    var socEndText by remember(charge.id) { mutableStateOf(charge.socEnd?.toString() ?: "") }
    var kwhText by remember(charge.id) {
        mutableStateOf(charge.kwhCharged?.let { "%.2f".format(it) } ?: "")
    }
    // tariff_per_kwh = cost / kwh (existing record), либо settings default по типу
    var tariffText by remember(charge.id) {
        val existing = if (charge.cost != null && (charge.kwhCharged ?: 0.0) > 0.01)
            charge.cost / charge.kwhCharged!! else null
        val initial = existing ?: if (type == "DC") dcTariff else homeTariff
        mutableStateOf("%.3f".format(initial))
    }

    // На переключение AC↔DC автоподставка тарифа (только если юзер не редактировал поле)
    var tariffEditedByUser by remember(charge.id) { mutableStateOf(false) }
    LaunchedEffect(type) {
        if (!tariffEditedByUser) {
            val auto = if (type == "DC") dcTariff else homeTariff
            tariffText = "%.3f".format(auto)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = MutableInteractionSource()
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth(0.5f)
                    .clickable(
                        indication = null,
                        interactionSource = MutableInteractionSource()
                    ) { /* absorb */ }
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Изменить зарядку", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    // Type radio
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Тип:", color = TextSecondary, fontSize = 13.sp,
                            modifier = Modifier.width(80.dp))
                        TypeRadio("AC", type == "AC") { type = "AC" }
                        Spacer(modifier = Modifier.width(8.dp))
                        TypeRadio("DC", type == "DC") { type = "DC" }
                    }

                    // SOC start/end
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SOC %:", color = TextSecondary, fontSize = 13.sp,
                            modifier = Modifier.width(80.dp))
                        OutlinedTextField(
                            value = socStartText,
                            onValueChange = { socStartText = it.filter { ch -> ch.isDigit() }.take(3) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                        Text(" → ", color = TextMuted, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 4.dp))
                        OutlinedTextField(
                            value = socEndText,
                            onValueChange = { socEndText = it.filter { ch -> ch.isDigit() }.take(3) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                    }

                    // kWh
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("кВт·ч:", color = TextSecondary, fontSize = 13.sp,
                            modifier = Modifier.width(80.dp))
                        OutlinedTextField(
                            value = kwhText,
                            onValueChange = { kwhText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                    }

                    // Tariff per kWh
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$currencySymbol/кВт·ч:", color = TextSecondary, fontSize = 13.sp,
                            modifier = Modifier.width(80.dp))
                        OutlinedTextField(
                            value = tariffText,
                            onValueChange = {
                                tariffText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                                tariffEditedByUser = true
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                    }

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Отмена", color = TextSecondary, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val socStart = socStartText.toIntOrNull()
                                val socEnd = socEndText.toIntOrNull()
                                val kwh = kwhText.replace(',', '.').toDoubleOrNull()
                                val tariff = tariffText.replace(',', '.').toDoubleOrNull()
                                val cost = if (kwh != null && tariff != null) kwh * tariff else null
                                val kwhSoc = if (socStart != null && socEnd != null && socEnd > socStart)
                                    (socEnd - socStart) / 100.0 * batteryCapacityKwh else null
                                onSave(charge.copy(
                                    type = type,
                                    socStart = socStart,
                                    socEnd = socEnd,
                                    kwhCharged = kwh,
                                    kwhChargedSoc = kwhSoc,
                                    cost = cost,
                                ))
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Сохранить", color = NavyDark, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(end = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = AccentGreen, unselectedColor = TextMuted),
        )
        Text(label, color = TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentGreen,
    unfocusedBorderColor = CardBorder,
    cursorColor = AccentGreen,
)
```

- [ ] **Step 2: Build to verify imports compile**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. Если `darkFieldColors` уже определён где-то общим — заменить на тот common, или оставить privatе как здесь.

---

### Task 8: ChargesViewModel — handlers для action sheet/edit/delete

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/charges/ChargesViewModel.kt`

- [ ] **Step 1: Расширить state и добавить handlers**

В `ChargesViewModel.kt` добавить в `ChargesUiState` (после `capacitySeries: List<Float> = emptyList()` строка 65):

```kotlin
    val selectedChargeForAction: ChargeEntity? = null,
    val editingCharge: ChargeEntity? = null,
    val deleteConfirmCharge: ChargeEntity? = null,
    val homeTariff: Double = 0.20,
    val dcTariff: Double = 0.73,
```

В `init` блоке (строка 81), внутри существующего `viewModelScope.launch` (строка 82) добавить чтение тарифов:

```kotlin
            val home = settingsRepository.getHomeTariff()
            val dc = settingsRepository.getDcTariff()
            _uiState.update { it.copy(homeTariff = home, dcTariff = dc) }
```

В конец класса (перед закрывающей скобкой) добавить методы:

```kotlin
    fun onLongPressCharge(charge: ChargeEntity) {
        _uiState.update { it.copy(selectedChargeForAction = charge) }
    }

    fun onDismissActionSheet() {
        _uiState.update { it.copy(selectedChargeForAction = null) }
    }

    fun onEditCharge() {
        _uiState.update { it.copy(editingCharge = it.selectedChargeForAction, selectedChargeForAction = null) }
    }

    fun onDismissEdit() {
        _uiState.update { it.copy(editingCharge = null) }
    }

    fun onSaveEdit(updated: ChargeEntity) {
        viewModelScope.launch {
            chargeRepository.updateCharge(updated)
            _uiState.update { it.copy(editingCharge = null) }
            // Reload visible list — collect Flow в loadAll() сам подхватит обновлённую запись.
        }
    }

    fun onConfirmDeletePrompt() {
        _uiState.update { it.copy(deleteConfirmCharge = it.selectedChargeForAction, selectedChargeForAction = null) }
    }

    fun onDismissDeleteConfirm() {
        _uiState.update { it.copy(deleteConfirmCharge = null) }
    }

    fun onConfirmDelete() {
        viewModelScope.launch {
            val charge = _uiState.value.deleteConfirmCharge ?: return@launch
            chargeRepository.deleteCharge(charge)
            _uiState.update { it.copy(deleteConfirmCharge = null) }
        }
    }
```

NB: `chargeRepository.updateCharge` уже существует (строка 26 ChargeRepository). `deleteCharge` добавлен в Task 1.

- [ ] **Step 2: Add UI-layer filter for empty charges (sub-100Wh safety)**

В `loadAll` (строка 123), внутри `chargeRepository.getChargesByDateRange(...).collect { rawCharges ->`, перед фильтром по type, добавить фильтр пустоты:

Было:
```kotlin
                val filtered = when (typeFilter) {
                    ChargeTypeFilter.ALL -> rawCharges
                    ChargeTypeFilter.AC -> rawCharges.filter { it.gunState == 2 }
                    ChargeTypeFilter.DC -> rawCharges.filter { it.gunState in setOf(3, 4) }
                }
```

Заменить на:
```kotlin
                val nonEmpty = rawCharges.filter { (it.kwhCharged ?: 0.0) >= 0.05 }
                val filtered = when (typeFilter) {
                    ChargeTypeFilter.ALL -> nonEmpty
                    ChargeTypeFilter.AC -> nonEmpty.filter { it.gunState == 2 }
                    ChargeTypeFilter.DC -> nonEmpty.filter { it.gunState in setOf(3, 4) }
                }
```

- [ ] **Step 3: Build + run unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.ui.charges.*" 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL.

---

### Task 9: ChargesScreen — long-press, ModalBottomSheet, delete confirm, edit dialog

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/charges/ChargesScreen.kt`

- [ ] **Step 1: Make `ChargeRow` support long-press**

В `ChargesScreen.kt`:

В сигнатуру `ChargeRow` (строка 381) добавить параметр `onLongClick`:

```kotlin
@Composable
private fun ChargeRow(
    charge: ChargeEntity,
    currencySymbol: String,
    onLongClick: () -> Unit,
)
```

В теле `ChargeRow` импортировать `combinedClickable` (вверху файла, в imports):

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
```

И обернуть `Column { Row { ... } HorizontalDivider }` в `combinedClickable`. Заменить выражение `Column {` (строка 405) на:

```kotlin
    Column(
        modifier = Modifier.combinedClickable(
            onClick = { /* no-op для обычного клика */ },
            onLongClick = onLongClick,
        )
    ) {
```

И добавить `@OptIn(ExperimentalFoundationApi::class)` над `private fun ChargeRow`.

- [ ] **Step 2: Wire `onLongClick` from caller**

В `LazyColumn` (строки 105-146), найти вызов `ChargeRow(charge = charge, currencySymbol = state.currencySymbol)` (строка 135-138). Заменить на:

```kotlin
                                            ChargeRow(
                                                charge = charge,
                                                currencySymbol = state.currencySymbol,
                                                onLongClick = { viewModel.onLongPressCharge(charge) }
                                            )
```

- [ ] **Step 3: Add ModalBottomSheet + ChargeEditDialog + AlertDialog**

В импорты файла:

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
```

В функцию `ChargesScreen` (после `Column { ... }` блока, в конце функции, перед закрывающей скобкой `@Composable fun ChargesScreen`) добавить sheet/dialog логику. Поскольку существующая функция использует `Column { ... }` который заканчивается на строке 169 (закрывающая `}` для main Column), добавим sheet/dialog **внутри** main Column в самом конце:

Прямо перед закрывающим `}` главного `Column` (после `Row(modifier = Modifier.fillMaxSize()) { ... }` строка 168):

```kotlin
        // Action sheet (long-press)
        state.selectedChargeForAction?.let { charge ->
            ChargeActionSheet(
                charge = charge,
                onDismiss = { viewModel.onDismissActionSheet() },
                onEdit = { viewModel.onEditCharge() },
                onDeletePrompt = { viewModel.onConfirmDeletePrompt() },
            )
        }
        // Edit dialog
        state.editingCharge?.let { charge ->
            ChargeEditDialog(
                charge = charge,
                homeTariff = state.homeTariff,
                dcTariff = state.dcTariff,
                batteryCapacityKwh = state.nominalCapacityKwh,
                currencySymbol = state.currencySymbol,
                onDismiss = { viewModel.onDismissEdit() },
                onSave = { viewModel.onSaveEdit(it) },
            )
        }
        // Delete confirmation
        state.deleteConfirmCharge?.let { charge ->
            AlertDialog(
                onDismissRequest = { viewModel.onDismissDeleteConfirm() },
                title = { Text("Удалить зарядку?") },
                text = { Text("${charge.kwhCharged?.let { "%.1f".format(it) } ?: "—"} кВт·ч • восстановить будет нельзя.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.onConfirmDelete() }) {
                        Text("Удалить", color = SocRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDismissDeleteConfirm() }) {
                        Text("Отмена")
                    }
                },
                containerColor = CardSurface,
            )
        }
```

В импорты добавить:
```kotlin
import androidx.compose.material3.TextButton
```

Внизу файла перед последней закрывающей скобкой добавить новый composable `ChargeActionSheet`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargeActionSheet(
    charge: ChargeEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDeletePrompt: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${charge.type ?: "—"} • ${charge.kwhCharged?.let { "%.1f".format(it) } ?: "—"} кВт·ч",
                color = TextSecondary, fontSize = 12.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Изменить", color = TextPrimary, fontSize = 16.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDeletePrompt).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Удалить", color = SocRed, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL.

---

### Task 10: BatteryHealthDialog (заменяет BatteryHealthScreen)

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/ui/battery/BatteryHealthDialog.kt`
- Delete: `app/src/main/kotlin/com/bydmate/app/ui/battery/BatteryHealthScreen.kt`

- [ ] **Step 1: Создать BatteryHealthDialog.kt с composable**

Полный новый файл `BatteryHealthDialog.kt`:

```kotlin
package com.bydmate.app.ui.battery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.ui.theme.*

/**
 * Dialog version of the former BatteryHealthScreen — opens from DashboardScreen
 * when the user taps the small battery card. Width 540 dp like AI Insight dialog.
 *
 * `liveSoh`, `liveLifetimeKm`, `liveLifetimeKwh` come from BatteryStateRepository
 * (passed in by Dashboard). History (charges/snapshots, charts) loaded by the
 * existing BatteryHealthViewModel — no changes needed there.
 */
@Composable
fun BatteryHealthDialog(
    liveSoh: Float?,
    liveLifetimeKm: Float?,
    liveLifetimeKwh: Float?,
    borderColor: Color,
    onDismiss: () -> Unit,
    viewModel: BatteryHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = MutableInteractionSource()
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(2.dp, borderColor.copy(alpha = 0.6f)),
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth(0.55f)
                    .clickable(
                        indication = null,
                        interactionSource = MutableInteractionSource()
                    ) { /* absorb */ }
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Здоровье батареи", color = borderColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    // Live snapshot from BMS (autoservice)
                    BmsLiveBlock(
                        soh = liveSoh,
                        lifetimeKm = liveLifetimeKm,
                        lifetimeKwh = liveLifetimeKwh,
                    )

                    // Cell delta + 12V min/avg
                    Row(
                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatColumn("Текущ. дельта", state.currentDelta?.let { "%.3fV".format(it) } ?: "—")
                        StatColumn("Средн. дельта", state.avgDelta?.let { "%.3fV".format(it) } ?: "—")
                        StatColumn("12V мин.", state.minVoltage12v?.let { "%.1fV".format(it) } ?: "—")
                    }

                    // Cell voltage delta chart
                    val deltaValues = state.charges.reversed().mapNotNull { c ->
                        if (c.cellVoltageMax != null && c.cellVoltageMin != null)
                            c.cellVoltageMax - c.cellVoltageMin else null
                    }
                    if (deltaValues.size >= 2) {
                        Text("Баланс ячеек (Δ V)", color = TextSecondary, fontSize = 12.sp)
                        LineChart(
                            values = deltaValues, lineColor = AccentGreen,
                            warningThreshold = 0.05, criticalThreshold = 0.10,
                            formatLabel = { "%.3f".format(it) },
                            modifier = Modifier.fillMaxWidth().height(110.dp)
                        )
                    }

                    // 12V chart
                    val voltage12vValues = state.charges.reversed().mapNotNull { it.voltage12v }
                    if (voltage12vValues.size >= 2) {
                        Text("Бортовая сеть 12V", color = TextSecondary, fontSize = 12.sp)
                        LineChart(
                            values = voltage12vValues, lineColor = AccentBlue,
                            warningThreshold = 12.4, criticalThreshold = 11.8,
                            invertThresholds = true,
                            formatLabel = { "%.1f".format(it) },
                            modifier = Modifier.fillMaxWidth().height(110.dp)
                        )
                    }

                    // SOH/Capacity history (если набралось ≥2 снимков)
                    val sohValues = state.snapshots.reversed().mapNotNull { it.sohPercent }
                    if (sohValues.size >= 2) {
                        Text("История SOH", color = TextSecondary, fontSize = 12.sp)
                        LineChart(
                            values = sohValues, lineColor = AccentGreen,
                            warningThreshold = 90.0, criticalThreshold = 80.0,
                            invertThresholds = true,
                            formatLabel = { "%.1f".format(it) },
                            modifier = Modifier.fillMaxWidth().height(110.dp)
                        )
                    }

                    if (state.charges.isEmpty() && state.snapshots.isEmpty() && !state.isLoading) {
                        Text(
                            "История появится после первой полной зарядки. SoH и пробег от BMS видны выше — обновляются на каждом запуске.",
                            color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BmsLiveBlock(soh: Float?, lifetimeKm: Float?, lifetimeKwh: Float?) {
    val avgPer100 = if (lifetimeKm != null && lifetimeKwh != null && lifetimeKm > 0)
        lifetimeKwh / lifetimeKm * 100.0 else null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Сейчас от BMS", color = TextMuted, fontSize = 10.sp, letterSpacing = 0.3.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BmsValue("SoH", soh?.let { "%.0f%%".format(it) } ?: "—", AccentGreen)
            BmsValue("Пробег", lifetimeKm?.let { "%.0f км".format(it) } ?: "—", TextPrimary)
            BmsValue("Прокачано", lifetimeKwh?.let { "%.0f кВт·ч".format(it) } ?: "—", TextPrimary)
            BmsValue("Расход /100км",
                avgPer100?.let { "%.1f".format(it) } ?: "—",
                AccentBlue)
        }
    }
}

@Composable
private fun BmsValue(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun LineChart(
    values: List<Double>,
    lineColor: Color,
    warningThreshold: Double,
    criticalThreshold: Double,
    invertThresholds: Boolean = false,
    formatLabel: (Double) -> String,
    modifier: Modifier = Modifier
) {
    if (values.size < 2) return
    val minVal = values.min()
    val maxVal = values.max()
    val range = (maxVal - minVal).coerceAtLeast(0.001)
    Canvas(modifier = modifier.background(CardSurface, RoundedCornerShape(8.dp)).padding(8.dp)) {
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1).coerceAtLeast(1)
        fun yForValue(v: Double): Float = (h - ((v - minVal) / range * h)).toFloat()
        if (warningThreshold in minVal..maxVal) {
            val y = yForValue(warningThreshold)
            drawLine(SocYellow.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (criticalThreshold in minVal..maxVal) {
            val y = yForValue(criticalThreshold)
            drawLine(SocRed.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = yForValue(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = yForValue(v)
            val dotColor = if (invertThresholds) {
                when {
                    v < criticalThreshold -> SocRed
                    v < warningThreshold -> SocYellow
                    else -> lineColor
                }
            } else {
                when {
                    v > criticalThreshold -> SocRed
                    v > warningThreshold -> SocYellow
                    else -> lineColor
                }
            }
            drawCircle(dotColor, radius = 4f, center = Offset(x, y))
        }
    }
}
```

- [ ] **Step 2: Delete old BatteryHealthScreen.kt**

Run: `git rm app/src/main/kotlin/com/bydmate/app/ui/battery/BatteryHealthScreen.kt`

Если IDE подсветит unused-import `BatteryHealthScreen` в `AppNavigation.kt` — он будет удалён в Task 12.

- [ ] **Step 3: Build (expect compile errors from AppNavigation/Dashboard — те будут пофиксены в Tasks 11-12)**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: либо BUILD SUCCESSFUL (если Kotlin не видит unresolved пока имя BatteryHealthScreen используется только в AppNavigation), либо compile error в `AppNavigation.kt` про `BatteryHealthScreen` — это нормально, продолжаем.

---

### Task 11: DashboardViewModel + DashboardScreen — малая 3-карточка + dialog

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Расширить DashboardUiState (live SoH/lifetime)**

В `DashboardViewModel.kt` в `DashboardUiState` (строка 30-78) добавить после `adbConnected: Boolean? = null` (строка 77):

```kotlin
    val currentSoh: Float? = null,
    val currentLifetimeKm: Float? = null,
    val currentLifetimeKwh: Float? = null,
```

- [ ] **Step 2: Расширить `loadAutoserviceFlag` чтобы хранить SoH/lifetime**

Заменить `loadAutoserviceFlag()` целиком (строки 359-365):

```kotlin
    private suspend fun loadAutoserviceFlag() {
        val enabled = settingsRepository.isAutoserviceEnabled()
        if (!enabled) {
            _uiState.update {
                it.copy(adbConnected = null, currentSoh = null,
                        currentLifetimeKm = null, currentLifetimeKwh = null)
            }
            return
        }
        val state = runCatching { batteryStateRepository.refresh() }.getOrNull()
        _uiState.update {
            if (state == null) {
                it.copy(adbConnected = false)
            } else {
                it.copy(
                    adbConnected = state.autoserviceAvailable,
                    currentSoh = state.sohPercent,
                    currentLifetimeKm = state.lifetimeKm,
                    currentLifetimeKwh = state.lifetimeKwh,
                )
            }
        }
    }
```

- [ ] **Step 3: Добавить новый composable `BatteryCompactCard` 3-кол. в DashboardScreen**

В `DashboardScreen.kt` после функции `CompactCard` (строка 512), добавить:

```kotlin
@Composable
private fun BatteryCompactCard(
    sohText: String,
    sohColor: Color,
    tempText: String,
    tempColor: Color,
    voltageText: String,
    voltageColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BatteryCell(value = sohText, label = "SoH", color = sohColor)
            BatteryCell(value = tempText, label = "темп. бат.", color = tempColor)
            BatteryCell(value = voltageText, label = "борт. сеть", color = voltageColor)
        }
    }
}

@Composable
private fun BatteryCell(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}
```

- [ ] **Step 4: Заменить вызов CompactCard для батареи на BatteryCompactCard**

В `DashboardScreen.kt` сигнатуру `DashboardScreen` (строки 58-62):

Было:
```kotlin
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigateBatteryHealth: () -> Unit = {}
) {
```

Заменить на:
```kotlin
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
) {
```

В блоке "// Battery card" (строки 148-163), заменить `CompactCard(...)` целиком на:

```kotlin
                        // Battery card — 3 значения: SoH | темп. батареи | бортовая сеть
                        val sohStatus = when {
                            (state.currentSoh ?: 100f) < 80f -> "critical"
                            (state.currentSoh ?: 100f) < 90f -> "warning"
                            else -> "ok"
                        }
                        val sohColor = when (sohStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow; else -> AccentGreen
                        }
                        val tempColor = when (state.batteryHealthStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow; else -> AccentGreen
                        }
                        val voltageColor = when (state.voltage12vStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow; else -> AccentGreen
                        }
                        val worstColor = when {
                            sohStatus == "critical" || state.batteryHealthStatus == "critical" || state.voltage12vStatus == "critical" -> SocRed
                            sohStatus == "warning" || state.batteryHealthStatus == "warning" || state.voltage12vStatus == "warning" -> SocYellow
                            else -> AccentGreen
                        }
                        BatteryCompactCard(
                            sohText = state.currentSoh?.let { "%.0f%%".format(it) } ?: "—",
                            sohColor = sohColor,
                            tempText = state.avgBatTemp?.let { "${it}°" } ?: "—",
                            tempColor = tempColor,
                            voltageText = state.voltage12v?.let { "%.1fВ".format(it) } ?: "—",
                            voltageColor = voltageColor,
                            borderColor = worstColor,
                            onClick = { viewModel.toggleBatteryHealthExpanded() }
                        )
```

- [ ] **Step 5: Render BatteryHealthDialog when expanded**

В блоке `if (state.idleDrainExpanded) { ... }` секции (строки 285-304), сразу **перед** `if (state.idleDrainExpanded)` добавить:

```kotlin
                    if (state.batteryHealthExpanded) {
                        val sohForColor = state.currentSoh ?: 100f
                        val color = when {
                            sohForColor < 80f || state.batteryHealthStatus == "critical" || state.voltage12vStatus == "critical" -> SocRed
                            sohForColor < 90f || state.batteryHealthStatus == "warning" || state.voltage12vStatus == "warning" -> SocYellow
                            else -> AccentGreen
                        }
                        com.bydmate.app.ui.battery.BatteryHealthDialog(
                            liveSoh = state.currentSoh,
                            liveLifetimeKm = state.currentLifetimeKm,
                            liveLifetimeKwh = state.currentLifetimeKwh,
                            borderColor = color,
                            onDismiss = { viewModel.toggleBatteryHealthExpanded() },
                        )
                    }
```

- [ ] **Step 6: Build and run unit tests**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL.

---

### Task 12: AppNavigation — убрать battery_health route

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/navigation/AppNavigation.kt`

- [ ] **Step 1: Remove battery_health imports + routes**

В `AppNavigation.kt`:

Удалить строку 55: `import com.bydmate.app.ui.battery.BatteryHealthScreen`.

В `composable(Screen.Dashboard.route)` (строки 211-213) заменить:
Было:
```kotlin
            composable(Screen.Dashboard.route) {
                DashboardScreen(onNavigateBatteryHealth = { navController.navigate("battery_health") })
            }
```
На:
```kotlin
            composable(Screen.Dashboard.route) {
                DashboardScreen()
            }
```

Удалить строку 220: `composable("battery_health") { BatteryHealthScreen() }`.

- [ ] **Step 2: Build to verify nothing else references BatteryHealthScreen**

Run: `./gradlew :app:assembleRelease 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 4: Commit 2.2**

```bash
git add app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsScreen.kt \
        app/src/main/kotlin/com/bydmate/app/ui/charges/ChargesScreen.kt \
        app/src/main/kotlin/com/bydmate/app/ui/charges/ChargesViewModel.kt \
        app/src/main/kotlin/com/bydmate/app/ui/charges/ChargeEditDialog.kt \
        app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt \
        app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardScreen.kt \
        app/src/main/kotlin/com/bydmate/app/ui/battery/BatteryHealthDialog.kt \
        app/src/main/kotlin/com/bydmate/app/ui/navigation/AppNavigation.kt
git rm app/src/main/kotlin/com/bydmate/app/ui/battery/BatteryHealthScreen.kt

git commit -m "$(cat <<'EOF'
feat(ui): v2.4.16 battery dialog + charge edit + Wi-Fi hint cleanup

- Settings: убрана подсказка про Wi-Fi (ADB on-device — loopback,
  Wi-Fi не нужен; заменена на полезный hint про Settings разработчика)
- Charges: long-press на ChargeRow → ModalBottomSheet
  («Изменить» / «Удалить») → ChargeEditDialog (тип, SOC, кВт·ч,
  тариф_за_кВт·ч с автоподставкой при AC↔DC) + delete confirm
- Charges UI: фильтр пустых зарядок (kwh < 0.05) на UI-слое,
  как страховка до запуска сервиса
- Dashboard: малая батарея 2-зн → 3-зн (SoH | темп.батареи | 12V),
  цвет рамки = worst-of {SoH, temp, 12V}
- BatteryHealthScreen → BatteryHealthDialog (540dp, scroll, в стиле
  AI Insight): блок «Сейчас от BMS» (SoH/пробег/прокачано/
  расход_/100км — наш расчёт kwh/km*100, не китайский LIFETIME_AVG_PHM)
- AppNavigation: убран полноэкранный battery_health route

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Commit 2.3 — release

### Task 13: Bump version + assembleRelease

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump versionCode + versionName**

В `app/build.gradle.kts` строки 16-17:

Было:
```kotlin
        versionCode = 255
        versionName = "2.4.15"
```

На:
```kotlin
        versionCode = 256
        versionName = "2.4.16"
```

- [ ] **Step 2: assembleRelease + sign**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleRelease 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`. Output APK: `app/build/outputs/apk/release/BYDMate-v2.4.16.apk`.

Run sign:
```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
  --ks bydmate-release.jks --ks-key-alias bydmate \
  --ks-pass pass:bydmate123 --key-pass pass:bydmate123 \
  --out BYDMate-v2.4.16.apk app/build/outputs/apk/release/BYDMate-v2.4.16.apk
```
Expected: signed APK in repo root: `BYDMate-v2.4.16.apk`.

NB: keystore лежит в `bydmate-release.jks` (project root) или `/Users/mac_andy/Projects/byd-scenes/bydmate-release.jks` — проверить какой существует первым через `ls bydmate-release.jks 2>/dev/null && echo HERE`. Если в byd-scenes — использовать абсолютный путь `--ks /Users/mac_andy/Projects/byd-scenes/bydmate-release.jks`.

- [ ] **Step 3: Push APK to DiLink**

Run:
```bash
adb -s 192.168.2.68:5555 push BYDMate-v2.4.16.apk /sdcard/Download/
```
Expected: `BYDMate-v2.4.16.apk: 1 file pushed`.

NB: если `adb` не connected — `adb connect 192.168.2.68:5555` сначала.

- [ ] **Step 4: Codex audit перед release**

Запустить codex-rescue subagent:
```
Audit branch feature/autoservice-readonly diff `850d8d8..HEAD` для v2.4.16 release.
Focus: ChargeDao.deleteEmpty (race с runCatchUp в TrackingService.onCreate?),
AutoserviceClient.isAvailable() через 3 fid'а (если все returns null —
fallback chain корректно завершается?), ChargeEditDialog math
(cost = kwh*tariff, kwh_charged_soc формула при socEnd<=socStart),
BatteryHealthDialog (utiliziing live BatteryStateRepository data — не залип ли стейт
при autoserviceAvailable=false). Report: real bugs vs nitpicks.
```

Если codex выдал blocker — починить, ещё раз assembleRelease, и только потом продолжить.

- [ ] **Step 5: Smoke test (Andy ставит на DiLink)**

User story:
1. Открыть BYDMate v2.4.16 на Leopard 3.
2. Dashboard — малая батарея показывает SoH=100% / темп.бат XX° / 12В YY.
3. Tap по батарее — открывается BatteryHealthDialog с блоком «Сейчас от BMS» (SoH/пробег/прокачано/расход/100км).
4. Зарядки — 3 пустые сессии исчезли, есть только настоящие.
5. Long-press на любой зарядке — bottom sheet «Изменить»/«Удалить».
6. «Изменить» → диалог с предзаполненными полями. Сменить AC↔DC → тариф автоподставляется. Сохранить → запись обновлена.
7. «Удалить» → confirm dialog → запись пропала.
8. Settings → Системные данные → подсказка про Wi-Fi отсутствует.

- [ ] **Step 6: Commit 2.3 (если все шаги ОК)**

```bash
git add app/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(release): bump to v2.4.16

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: GitHub release**

```bash
git push origin feature/autoservice-readonly
gh release create v2.4.16 BYDMate-v2.4.16.apk \
  --title "v2.4.16 — Системные данные: SoH батареи, редактирование зарядок" \
  --notes "Что нового:

- Малая карточка батареи на Главной: SoH | температура батареи | бортовая сеть
- Тап по карточке открывает диалог с подробной статистикой: SoH, истинный пробег от BMS, прокачано кВт·ч, расход кВт·ч/100км
- Зарядки: долгое нажатие — изменить (тип/SOC/кВт·ч/тариф) или удалить
- Зарядки автоматически фильтруются от шумовых записей < 0.05 кВт·ч
- Настройки → Системные данные: убран ложный совет про Wi-Fi
"
```

NB: changelog plain text, без markdown headers/bold/code (по `feedback_release_notes_no_markdown` — UpdateDialog показывает markdown буквально).

---

## Self-Review Checklist (run by plan author before handoff)

### 1. Spec coverage

- A6 deleteEmpty + safety floor — Task 1, 2 ✓
- A3 diag-логи в детектор + sentinel-логи — Task 2, 3 ✓
- A4 isAvailable через 3 fid'а — Task 4 ✓
- TrackingService integration — Task 5 ✓
- A5 Wi-Fi hint — Task 6 ✓
- A7 ChargeEditDialog + long-press + delete — Tasks 7, 8, 9 ✓
- B BatteryCompactCard 3-zn + BatteryHealthDialog — Tasks 10, 11, 12 ✓
- 2.3 release — Tasks 13 ✓
- 5 открытых вопросов — отвечены в `Architecture` и `Notes` секциях ✓

### 2. Placeholder scan

- Нет TBD/TODO/«implement later» — каждый код-блок содержит финальный код
- Все тесты прописаны полностью (не «similar to test N»)
- Все commit-сообщения готовы

### 3. Type consistency

- `ChargeDao.deleteEmpty(): Int` (Room возвращает count удалённых) ✓
- `ChargeDao.delete(charge: ChargeEntity)` ✓
- `ChargeRepository.deleteEmpty(): Int`, `deleteCharge(charge)` ✓
- `BatteryReading.sohPercent: Float?` (ChECK: уже Float? в коде, не Int. ОК) ✓
- `DashboardUiState.currentSoh: Float?`, `currentLifetimeKm: Float?`, `currentLifetimeKwh: Float?` ✓
- `BatteryHealthDialog(liveSoh: Float?, liveLifetimeKm: Float?, liveLifetimeKwh: Float?, borderColor, onDismiss, viewModel)` ✓
- `ChargeEditDialog(charge, homeTariff, dcTariff, batteryCapacityKwh, currencySymbol, onDismiss, onSave)` ✓
- `ChargesViewModel.onLongPressCharge(charge)`, `onSaveEdit(updated)`, `onConfirmDelete()` — последовательно ✓

### 4. Out-of-scope NOT touched

- Phase 3 (live tick детектора) — не трогаем ✓
- ChargingPrompt toggle — оставляем как есть ✓
- DiPlus ChargingLog import — не возвращаем ✓
- BatteryHealthViewModel — НЕ модифицируем (используем как есть) ✓

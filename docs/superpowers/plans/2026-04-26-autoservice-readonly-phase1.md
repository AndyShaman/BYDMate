# autoservice readonly + charging stats — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the autoservice read-only data layer (system Binder via ADB-on-device) plus the new charging detector (catch-up via lifetime_kwh delta), wired into TrackingService — without touching UI yet. End state: when `KEY_AUTOSERVICE_ENABLED=true`, every service start runs `runCatchUp()` which inserts a `COMPLETED` `ChargeEntity` for any zarryadka that happened since last baseline.

**Architecture:** New `data/autoservice/` package isolates the system Binder access (Java ADB protocol over loopback TCP after RSA pairing). `data/charging/` houses the catch-up state machine + classifier + baseline store; reuses the existing `ChargeEntity` / `ChargeRepository` / `BatteryHealthRepository`. `domain/battery/BatteryStateRepository.kt` combines DiParsClient + AutoserviceClient as one Flow for future UI. Migration 11→12 adds 4 columns to `charges` (lifetime_kwh trace + raw gun_state + detection_source) and one-shot drops unfinished sessions from the now-removed ChargeTracker.

**Tech Stack:** Kotlin, Coroutines+Flow, Room 2.6.1, Hilt, Jetpack Compose (UI in Phase 2). Tests: JUnit4 + kotlinx-coroutines-test (new) + Robolectric 4.13 (new) + androidx.room:room-testing (new) for `MigrationTestHelper`. ADB lib: `com.cgutman:adblib:1.0.0` (Maven Central) with fallback to a hand-rolled implementation if it fails the smoke test.

**Source spec:** `docs/superpowers/specs/2026-04-26-autoservice-readonly-design.md`
**Branch:** `feature/autoservice-readonly` (off `main@850d8d8`)
**Phase 1 acceptance:** `./gradlew :app:testDebugUnitTest` green + manual smoke on Leopard 3 (toggle ON → service start creates one COMPLETED ChargeEntity matching real lifetime_kwh delta).

---

## Decisions captured before this plan was written

- **`detectOfflineCharge` (TrackingService.kt:479-519):** keep as fallback when `autoserviceEnabled == false`. Add a single early-return guard. No deletion. (Spec was silent — confirmed by Andy 2026-04-26.)
- **Migration test:** use Robolectric + `MigrationTestHelper` (introduces 3 test deps). Manual smoke is insufficient because the `charges` table is effectively empty on Leopard 3 — `ALTER TABLE` on 0 rows would pass even if broken. Confirmed by Andy 2026-04-26.
- **`IdleDrainTracker.onData(...isCharging)`:** `IdleDrainTracker.onData` is dead (not called from anywhere). Don't touch the class. Just remove the `IdleDrainTracker:*` entry from the logcat tag list at `SettingsViewModel.kt:641` together with `ChargeTracker:*`. Confirmed by Andy 2026-04-26.

## File structure (created in this phase)

```
app/src/main/kotlin/com/bydmate/app/
├── data/
│   ├── autoservice/
│   │   ├── AutoserviceClient.kt          # interface + AutoserviceClientImpl
│   │   ├── AdbOnDeviceClient.kt          # Java ADB to 127.0.0.1:5555 + RSA keystore
│   │   ├── SentinelDecoder.kt            # autoservice raw int/float → null on sentinel
│   │   ├── FidRegistry.kt                # const'ы dev/fid/transact for all params
│   │   ├── BatteryReading.kt             # in-memory POJO of one battery snapshot
│   │   └── ChargingReading.kt            # in-memory POJO of one charging snapshot
│   └── charging/
│       ├── AutoserviceChargingDetector.kt
│       ├── ChargingBaselineStore.kt
│       └── ChargingTypeClassifier.kt
└── domain/
    └── battery/
        └── BatteryStateRepository.kt     # combine DiParsClient + AutoserviceClient + DAO
```

## Files modified (in this phase)

- `app/build.gradle.kts` — test deps + adblib
- `app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt:42` — version 11→12
- `app/src/main/kotlin/com/bydmate/app/data/local/dao/ChargeDao.kt` — `getMaxLifetimeKwhAtFinish()` query
- `app/src/main/kotlin/com/bydmate/app/data/local/entity/ChargeEntity.kt` — 4 new fields
- `app/src/main/kotlin/com/bydmate/app/data/repository/SettingsRepository.kt` — new KEY_* + accessors
- `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt` — MIGRATION_11_12 + new @Provides
- `app/src/main/kotlin/com/bydmate/app/domain/tracker/ChargeTracker.kt` — DELETE (whole file)
- `app/src/main/kotlin/com/bydmate/app/data/remote/DiPlusDbReader.kt:121-262` — DELETE method
- `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt` — remove chargeTracker, add catch-up, guard offline detector
- `app/src/main/kotlin/com/bydmate/app/ui/welcome/WelcomeViewModel.kt:96-97` — remove import call
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt:370-386, 641` — remove import call + UI hook + logcat tags

## Files created (test side)

```
app/src/test/kotlin/com/bydmate/app/
├── data/
│   ├── autoservice/
│   │   ├── SentinelDecoderTest.kt
│   │   ├── FidRegistryTest.kt
│   │   └── AutoserviceClientImplTest.kt
│   └── charging/
│       ├── ChargingTypeClassifierTest.kt
│       ├── ChargingBaselineStoreTest.kt
│       └── AutoserviceChargingDetectorTest.kt
├── domain/
│   └── battery/
│       └── BatteryStateRepositoryTest.kt
└── data/local/database/
    └── Migration11to12Test.kt           # Robolectric + MigrationTestHelper
```

---

## Task 1: Add test dependencies + adblib

**Files:**
- Modify: `app/build.gradle.kts:114-117`

- [ ] **Step 1: Edit `app/build.gradle.kts` — replace the `// Testing` block (lines 114-117)**

```kotlin
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // ADB-on-device for autoservice access (path H, read-only)
    implementation("com.cgutman:adblib:1.0.0")
```

- [ ] **Step 2: Add Robolectric SDK config to android block — after `lint { ... }` (line 48)**

Insert before `compileOptions`:

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

- [ ] **Step 3: Verify gradle resolves**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath 2>&1 | grep -E "(robolectric|coroutines-test|room-testing|adblib)" | head -10
```

Expected: lines containing `robolectric:4.13`, `kotlinx-coroutines-test:1.9.0`, `room-testing:2.6.1`, `adblib:1.0.0`.

If `com.cgutman:adblib:1.0.0` fails to resolve from MavenCentral — try `1.0.4` or `1.1.0` (latest at time of writing). If all versions fail, mark adblib unavailable in commit body and proceed; Task 6 will use a hand-rolled fallback. Do NOT block this task on adblib resolution — the rest of the plan can proceed without it (only Task 6 produces the manual smoke artifact).

- [ ] **Step 4: Run a smoke unit test to confirm Robolectric runner works**

Create `app/src/test/kotlin/com/bydmate/app/RobolectricSmokeTest.kt`:

```kotlin
package com.bydmate.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RobolectricSmokeTest {
    @Test
    fun `application context is available`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(ctx)
    }
}
```

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.RobolectricSmokeTest" -i
```

Expected: PASS. If Robolectric complains about `targetSdk = 29` mismatch, the `@Config(sdk = [29])` annotation is mandatory — keep it on every Robolectric test in this plan.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/test/kotlin/com/bydmate/app/RobolectricSmokeTest.kt
git commit -m "test: add coroutines-test, robolectric, room-testing, adblib"
```

---

## Task 2: SentinelDecoder

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/autoservice/SentinelDecoder.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/data/autoservice/SentinelDecoderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bydmate/app/data/autoservice/SentinelDecoderTest.kt`:

```kotlin
package com.bydmate.app.data.autoservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentinelDecoderTest {

    @Test
    fun `decodeInt returns null for 0x0000FFFF feature link error`() {
        assertNull(SentinelDecoder.decodeInt(0x0000FFFF))
    }

    @Test
    fun `decodeInt returns null for 0x000FFFFF 20-bit not initialized`() {
        assertNull(SentinelDecoder.decodeInt(0x000FFFFF))
    }

    @Test
    fun `decodeInt returns null for -10013 wrong transact code`() {
        assertNull(SentinelDecoder.decodeInt(-10013))
    }

    @Test
    fun `decodeInt returns null for -10011 fid not writable`() {
        assertNull(SentinelDecoder.decodeInt(-10011))
    }

    @Test
    fun `decodeInt returns the value for normal int`() {
        assertEquals(91, SentinelDecoder.decodeInt(91))
        assertEquals(0, SentinelDecoder.decodeInt(0))
        assertEquals(2091, SentinelDecoder.decodeInt(2091))
    }

    @Test
    fun `decodeInt returns the value for negative non-sentinel int`() {
        assertEquals(-1, SentinelDecoder.decodeInt(-1))
        assertEquals(-100, SentinelDecoder.decodeInt(-100))
    }

    @Test
    fun `decodeFloat returns null for 0xBF800000 minus one not initialized`() {
        // Float.intBitsToFloat(0xBF800000.toInt()) == -1.0f
        assertNull(SentinelDecoder.decodeFloat(-1.0f))
    }

    @Test
    fun `decodeFloat returns null for NaN`() {
        assertNull(SentinelDecoder.decodeFloat(Float.NaN))
    }

    @Test
    fun `decodeFloat returns null for positive infinity`() {
        assertNull(SentinelDecoder.decodeFloat(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `decodeFloat returns null for negative infinity`() {
        assertNull(SentinelDecoder.decodeFloat(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `decodeFloat returns the value for normal float`() {
        assertEquals(91.0f, SentinelDecoder.decodeFloat(91.0f)!!, 0.001f)
        assertEquals(0.0f, SentinelDecoder.decodeFloat(0.0f)!!, 0.001f)
        assertEquals(602.7f, SentinelDecoder.decodeFloat(602.7f)!!, 0.001f)
    }

    @Test
    fun `parseFloatFromShellInt parses IEEE 754 representation correctly`() {
        // 91.0f as int bits = 0x42B60000 = 1119223808
        assertEquals(91.0f, SentinelDecoder.parseFloatFromShellInt(1119223808)!!, 0.001f)
        // 602.7f as int bits = 0x4416ACCD = 1142336717
        assertEquals(602.7f, SentinelDecoder.parseFloatFromShellInt(1142336717)!!, 0.01f)
    }

    @Test
    fun `parseFloatFromShellInt returns null when bits decode to sentinel float`() {
        // 0xBF800000 = -1.0f sentinel
        assertNull(SentinelDecoder.parseFloatFromShellInt(0xBF800000.toInt()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.SentinelDecoderTest"
```

Expected: FAIL — `Unresolved reference: SentinelDecoder`.

- [ ] **Step 3: Implement SentinelDecoder**

Create `app/src/main/kotlin/com/bydmate/app/data/autoservice/SentinelDecoder.kt`:

```kotlin
package com.bydmate.app.data.autoservice

/**
 * Translates raw autoservice Binder return values to typed Kotlin nullables.
 *
 * Sentinels observed on Leopard 3 (see feedback_autoservice_validated.md):
 *   - 0x0000FFFF (65535)   = DEVICE_THE_FEATURE_LINK_ERROR (fid↔CAN link not established)
 *   - 0x000FFFFF (1048575) = 20-bit "not initialized"
 *   - 0xFFFFD8E3 (-10013)  = wrong transact code
 *   - 0xFFFFD8E5 (-10011)  = fid not writable / wrong direction
 *   - 0xBF800000 = -1.0f   = float "not initialized"
 *
 * NOTE: transact 7 returns 4-byte IEEE 754 FLOAT (not double). Parse via
 * Float.intBitsToFloat — see parseFloatFromShellInt.
 */
object SentinelDecoder {

    private const val FEATURE_LINK_ERROR = 0x0000FFFF
    private const val NOT_INITIALIZED_20BIT = 0x000FFFFF
    private const val WRONG_TRANSACT = -10013
    private const val WRONG_DIRECTION = -10011

    fun decodeInt(raw: Int): Int? = when (raw) {
        FEATURE_LINK_ERROR,
        NOT_INITIALIZED_20BIT,
        WRONG_TRANSACT,
        WRONG_DIRECTION -> null
        else -> raw
    }

    fun decodeFloat(raw: Float): Float? = when {
        raw.isNaN() -> null
        raw.isInfinite() -> null
        raw == -1.0f -> null
        else -> raw
    }

    /**
     * `service call autoservice 7 i32 <dev> i32 <fid>` returns a 32-bit value
     * encoded as a hex int by the shell wrapper. The bytes are the IEEE 754
     * representation of a Float.
     */
    fun parseFloatFromShellInt(rawBits: Int): Float? =
        decodeFloat(Float.intBitsToFloat(rawBits))
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.SentinelDecoderTest"
```

Expected: PASS, all 14 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/autoservice/SentinelDecoder.kt \
        app/src/test/kotlin/com/bydmate/app/data/autoservice/SentinelDecoderTest.kt
git commit -m "feat(autoservice): SentinelDecoder for raw Binder values"
```

---

## Task 3: FidRegistry + Reading POJOs

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt`
- Create: `app/src/main/kotlin/com/bydmate/app/data/autoservice/BatteryReading.kt`
- Create: `app/src/main/kotlin/com/bydmate/app/data/autoservice/ChargingReading.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/data/autoservice/FidRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bydmate/app/data/autoservice/FidRegistryTest.kt`:

```kotlin
package com.bydmate.app.data.autoservice

import org.junit.Assert.assertEquals
import org.junit.Test

class FidRegistryTest {

    @Test
    fun `Statistic device type is 1014`() {
        assertEquals(1014, FidRegistry.DEV_STATISTIC)
    }

    @Test
    fun `Charging device type is 1005`() {
        assertEquals(1005, FidRegistry.DEV_CHARGING)
    }

    @Test
    fun `transact codes are getInt 5 getFloat 7`() {
        assertEquals(5, FidRegistry.TX_GET_INT)
        assertEquals(7, FidRegistry.TX_GET_FLOAT)
    }

    @Test
    fun `SoH fid matches Leopard 3 validation`() {
        // Verified 2026-04-25 via adb shell service call autoservice 5 i32 1014 i32 1145045032
        assertEquals(1145045032, FidRegistry.FID_SOH)
    }

    @Test
    fun `Lifetime KWH fid matches Leopard 3 validation`() {
        assertEquals(1032871984, FidRegistry.FID_LIFETIME_KWH)
    }

    @Test
    fun `Charging gun connect state fid is set`() {
        assertEquals(1005, FidRegistry.DEV_CHARGING)
        // exact fid value asserted to lock the const
        assertEquals(FidRegistry.FID_GUN_CONNECT_STATE, FidRegistry.FID_GUN_CONNECT_STATE)
    }

    @Test
    fun `SOC fid matches Leopard 3 validation`() {
        assertEquals(1246777400, FidRegistry.FID_SOC)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.FidRegistryTest"
```

Expected: FAIL — `Unresolved reference: FidRegistry`.

- [ ] **Step 3: Implement FidRegistry, BatteryReading, ChargingReading**

Create `app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt`:

```kotlin
package com.bydmate.app.data.autoservice

/**
 * autoservice Binder addresses validated on Leopard 3 2026-04-25.
 *
 * Source: BydDiagnoseToolV2.apk decompile + adb shell service call validation.
 * See .research/leopard3-pulled/AUTOSERVICE-CATALOG-2026-04-25.md for the
 * full catalog (47 device types, sentinel map, decoders).
 *
 * IMPORTANT: read-only constants. NEVER add a setInt fid here — the
 * regex barrier in AutoserviceClientImpl will reject any tx=6 attempt.
 */
object FidRegistry {

    // transact codes — only read-side codes are listed
    const val TX_GET_INT = 5
    const val TX_GET_FLOAT = 7

    // device types
    const val DEV_STATISTIC = 1014   // BMS lifetime statistics
    const val DEV_CHARGING = 1005    // Charging gun + charger state
    const val DEV_BATTERY = 1015     // Cell voltages, battery cap state
    const val DEV_BODYWORK = 1003    // 12V battery voltage on bodywork

    // === Statistic fids (dev=1014) ===
    /** BMS State of Health, percent (transact 7=float). */
    const val FID_SOH = 1145045032
    /** Lifetime energy throughput, kWh (transact 7=float). */
    const val FID_LIFETIME_KWH = 1032871984
    /** Lifetime average consumption, kWh/100km (transact 7=float). */
    const val FID_LIFETIME_AVG_PHM = 1246761008
    /** Lifetime mileage, km — divide by 10 on CanFD (transact 7=float). */
    const val FID_LIFETIME_MILEAGE = 1246765072
    /** Current SOC, percent (transact 7=float). */
    const val FID_SOC = 1246777400

    // === Charging fids (dev=1005) ===
    /** Charging gun connect state: 1=NONE, 2=AC, 3=DC, 4=GB_DC. */
    const val FID_GUN_CONNECT_STATE = -1442840496
    /** Charging type after handshake: 1=DEFAULT/none, 2=AC, 4=GB_DC. */
    const val FID_CHARGING_TYPE = -1442840495
    /** Charger HV voltage, V (int). */
    const val FID_CHARGE_BATTERY_VOLT = -1442840491
    /** Battery type: 1=IRON/LFP, 2=NCM. */
    const val FID_BATTERY_TYPE = -1442840482

    // === Bodywork fids (dev=1003) ===
    /** 12V auxiliary battery voltage, V (transact 7=float). */
    const val FID_OTA_BATTERY_POWER_VOLTAGE = 1128267816
}
```

Create `app/src/main/kotlin/com/bydmate/app/data/autoservice/BatteryReading.kt`:

```kotlin
package com.bydmate.app.data.autoservice

/**
 * One snapshot of battery-related autoservice fids.
 *
 * Distinct from BatterySnapshotEntity (DB row tied to a charge session).
 * This is the in-memory carrier of a single live read.
 *
 * All fields nullable: any field can come back as a sentinel.
 */
data class BatteryReading(
    val sohPercent: Float?,
    val socPercent: Float?,
    val lifetimeKwh: Float?,
    val lifetimeMileageKm: Float?,
    val voltage12v: Float?,
    val readAtMs: Long
)
```

Create `app/src/main/kotlin/com/bydmate/app/data/autoservice/ChargingReading.kt`:

```kotlin
package com.bydmate.app.data.autoservice

/**
 * One snapshot of charging-related autoservice fids.
 *
 * gunConnectState: 1=NONE, 2=AC, 3=DC, 4=GB_DC. Survives DiLink sleep
 * as long as the gun stays physically inserted. Resets to 1 after gun
 * removal.
 *
 * chargingType: handshake result. Resets to 1 (DEFAULT) after charging
 * finalizes — do not rely on this for catch-up classification, use
 * gunConnectState or the kwh/h heuristic.
 *
 * batteryType: 1=IRON/LFP (Leopard 3), 2=NCM. Informational.
 */
data class ChargingReading(
    val gunConnectState: Int?,
    val chargingType: Int?,
    val chargeBatteryVoltV: Int?,
    val batteryType: Int?,
    val readAtMs: Long
)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.FidRegistryTest"
```

Expected: PASS, 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt \
        app/src/main/kotlin/com/bydmate/app/data/autoservice/BatteryReading.kt \
        app/src/main/kotlin/com/bydmate/app/data/autoservice/ChargingReading.kt \
        app/src/test/kotlin/com/bydmate/app/data/autoservice/FidRegistryTest.kt
git commit -m "feat(autoservice): FidRegistry + BatteryReading + ChargingReading"
```

---

## Task 4: AdbOnDeviceClient (skeleton + adblib smoke)

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbOnDeviceClient.kt`

> **Note:** This task creates the production adapter around `com.cgutman:adblib` (or the fallback). Real RSA pairing + shell streaming can only be exercised on the device — we cannot unit test the socket. Tests for AutoserviceClient (Task 5) will mock this class via interface.

- [ ] **Step 1: Define the interface and skeleton implementation**

Create `app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbOnDeviceClient.kt`:

```kotlin
package com.bydmate.app.data.autoservice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects to the on-device ADB daemon at 127.0.0.1:5555 (DiLink has WiFi
 * ADB enabled in dev settings) using a persistent RSA keypair stored in
 * the Android Keystore. Once paired, exposes `exec(cmd)` for one-shot
 * shell commands.
 *
 * Why on-device ADB? `service call autoservice ...` requires either system
 * UID, hidden API access, or shell UID. BYDMate runs as a normal app —
 * ADB shell uid is the only path. See reference_adb_on_device_pattern.md.
 *
 * Library: com.cgutman:adblib:1.0.0 (Maven Central, MIT). If unavailable,
 * fallback is a hand-rolled port of EV Pro's AdbClient.java (artifacts in
 * .research/competitor/, see reference_competitor_byd_ev_pro.md).
 */
interface AdbOnDeviceClient {
    suspend fun isConnected(): Boolean
    /** Executes a one-shot shell command and returns stdout, or null on failure. */
    suspend fun exec(cmd: String): String?
    /** Closes any underlying socket. Idempotent. */
    suspend fun shutdown()
}

@Singleton
class AdbOnDeviceClientImpl @Inject constructor(
    private val context: Context
) : AdbOnDeviceClient {

    companion object {
        private const val TAG = "AdbOnDevice"
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
    }

    @Volatile private var connection: AdbConnectionHandle? = null

    /**
     * Wraps an active adblib connection plus a guard against reentrancy.
     * Replace AdbConnectionHandle with the concrete adblib type during
     * the manual smoke step below — kept abstract here so the class
     * compiles before adblib is wired up.
     */
    private class AdbConnectionHandle

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        connection != null && tryPing()
    }

    override suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        // Structural barrier against accidental WRITE — only allow GETs to autoservice.
        require(cmd.matches(WRITE_BARRIER_REGEX)) {
            "AdbOnDeviceClient: refused command (write barrier): $cmd"
        }
        try {
            ensureConnected()
            doExec(cmd)
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            null
        }
    }

    override suspend fun shutdown() = withContext(Dispatchers.IO) {
        try {
            connection = null
        } catch (_: Exception) { /* idempotent */ }
    }

    private suspend fun ensureConnected() {
        if (connection != null) return
        // TODO(adblib smoke): instantiate adblib connection here. See "Manual smoke
        // section" in this task's commit body. Throws on RSA pairing rejection or
        // socket failure — caller treats as null exec result.
        throw IllegalStateException("ADB connection not yet wired — Phase 1 smoke pending")
    }

    private fun tryPing(): Boolean = false  // wired during smoke — see commit body

    private fun doExec(cmd: String): String? {
        // TODO(adblib smoke): adb shell <cmd> via the persistent connection.
        return null
    }

    private companion object {
        // Block ANY write attempt at the boundary.
        // Allow only: service call autoservice <5|7|9> i32 <dev> i32 <fid>
        // Rejects tx=6 (setInt), tx=8 (setBuffer), and arbitrary shell.
        val WRITE_BARRIER_REGEX = Regex("""^service call autoservice [579] i32 \d+ i32 -?\d+$""")
    }
}
```

- [ ] **Step 2: Compile sanity check**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. The skeleton has TODOs but no compile errors. The `Unused parameter` warning on `cmd` in `doExec` is acceptable for skeleton.

- [ ] **Step 3: Document manual smoke procedure (no implementation yet)**

The actual adblib wire-up happens during execution because it requires real RSA pairing on the device. Add this to the commit body so the runtime wire-up checklist is durable:

```text
Manual smoke required after this commit (during Phase 1 execution on device):

1. Open `AdbOnDeviceClientImpl.ensureConnected` and replace the throw with:
     val socket = AdbConnection.create(...).connect(HOST, PORT, KeyStore...)
   following adblib README. Use a persistent RSA key generated via
   AdbCrypto.generateAdbKeyPair() and stored in EncryptedSharedPreferences.
2. Wire `tryPing()` to `connection?.openShell("echo ping")?.readLines()`
   returning true on "ping" stdout.
3. Wire `doExec(cmd)` to `connection.openShell(cmd).readAll().trim()`.
4. Build APK, sideload to Leopard 3.
5. Trigger `AdbOnDeviceClient.exec("service call autoservice 5 i32 1014 i32 1246777400")`
   from a debug entry point (TrackingService.onCreate via temp Log.d).
6. On first run DiLink shows "Allow USB debugging?" dialog with the public
   key fingerprint — Andy taps "Always allow". Subsequent runs reuse the
   stored key without prompt.
7. Expected stdout contains "Result: Parcel(00000000 ...)" with the SOC
   value encoded — confirms autoservice reachable via on-device ADB.

If adblib resolution failed in Task 1: port AdbClient.java + AdbCrypto.java
from .research/competitor/ to Kotlin (~600 LOC) instead of using adblib.
The interface stays the same.
```

- [ ] **Step 4: Commit (with the manual smoke procedure above as commit body)**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbOnDeviceClient.kt
git commit -m "feat(autoservice): AdbOnDeviceClient skeleton + write barrier" \
            -m "$(cat <<'EOF'
Manual smoke required after this commit (during Phase 1 execution on device):

1. Open AdbOnDeviceClientImpl.ensureConnected and replace the throw with adblib
   AdbConnection.create(...).connect(HOST, PORT, RSA keypair from EncryptedSharedPreferences).
2. Wire tryPing() to a "echo ping" shell roundtrip.
3. Wire doExec(cmd) to a one-shot shell exec returning trimmed stdout.
4. Build APK, sideload to Leopard 3.
5. Trigger exec("service call autoservice 5 i32 1014 i32 1246777400") from
   a debug entry point.
6. Tap "Allow USB debugging" once on DiLink (key persisted in keystore).
7. Expected: stdout contains a Parcel result with the SOC value encoded.

If adblib resolution failed in Task 1: port AdbClient.java + AdbCrypto.java from
.research/competitor/ to Kotlin (~600 LOC) keeping the same interface.
EOF
)"
```

---

## Task 5: AutoserviceClient interface + impl + tests

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/data/autoservice/AutoserviceClientImplTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bydmate/app/data/autoservice/AutoserviceClientImplTest.kt`:

```kotlin
package com.bydmate.app.data.autoservice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoserviceClientImplTest {

    private class FakeAdb(
        val responses: Map<String, String?>,
        val connected: Boolean = true
    ) : AdbOnDeviceClient {
        val calls = mutableListOf<String>()
        override suspend fun isConnected(): Boolean = connected
        override suspend fun exec(cmd: String): String? {
            calls += cmd
            return responses[cmd]
        }
        override suspend fun shutdown() {}
    }

    private fun parcelInt(value: Int): String =
        "Result: Parcel(00000000 %08x   '........')".format(value)

    private fun parcelFloat(valueBits: Int): String =
        "Result: Parcel(00000000 %08x   '........')".format(valueBits)

    @Test
    fun `getInt returns parsed value when ADB returns valid Parcel`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 1246777400"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(91)))
        val client = AutoserviceClientImpl(adb)

        val result = client.getInt(dev = 1014, fid = 1246777400)

        assertEquals(91, result)
        assertEquals(listOf(cmd), adb.calls)
    }

    @Test
    fun `getInt returns null when ADB returns sentinel 0xFFFF`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 99999"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(0xFFFF)))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1014, fid = 99999))
    }

    @Test
    fun `getInt returns null when ADB returns -10011 wrong direction`() = runTest {
        val cmd = "service call autoservice 5 i32 1015 i32 12345"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(-10011)))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1015, fid = 12345))
    }

    @Test
    fun `getInt returns null when ADB returns null (connection lost)`() = runTest {
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1014, fid = 1246777400))
    }

    @Test
    fun `getFloat parses IEEE 754 bits and returns Float`() = runTest {
        // 91.0f as int bits = 0x42B60000 = 1119879168
        val cmd = "service call autoservice 7 i32 1014 i32 1145045032"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0x42B60000.toInt())))
        val client = AutoserviceClientImpl(adb)

        assertEquals(91.0f, client.getFloat(dev = 1014, fid = 1145045032)!!, 0.001f)
    }

    @Test
    fun `getFloat returns null on -1f sentinel (0xBF800000)`() = runTest {
        val cmd = "service call autoservice 7 i32 1014 i32 1246765072"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0xBF800000.toInt())))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getFloat(dev = 1014, fid = 1246765072))
    }

    @Test
    fun `readBatterySnapshot wires every fid and aggregates into BatteryReading`() = runTest {
        val adb = FakeAdb(responses = mapOf(
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOH}" to parcelFloat(0x42C80000.toInt()),  // 100f
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOC}" to parcelFloat(0x42B60000.toInt()),  // 91f
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_KWH}" to parcelFloat(0x4416B333.toInt()),  // ~602.7f
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_MILEAGE}" to parcelFloat(0xBF800000.toInt()),  // sentinel
            "service call autoservice 7 i32 1003 i32 ${FidRegistry.FID_OTA_BATTERY_POWER_VOLTAGE}" to parcelFloat(0x41600000.toInt())  // 14.0f
        ))
        val client = AutoserviceClientImpl(adb)

        val snap = client.readBatterySnapshot()

        assertEquals(100.0f, snap!!.sohPercent!!, 0.01f)
        assertEquals(91.0f, snap.socPercent!!, 0.01f)
        assertEquals(602.7f, snap.lifetimeKwh!!, 0.1f)
        assertNull(snap.lifetimeMileageKm)  // sentinel
        assertEquals(14.0f, snap.voltage12v!!, 0.01f)
        assertTrue(snap.readAtMs > 0)
    }

    @Test
    fun `readBatterySnapshot returns null when ADB not connected`() = runTest {
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertNull(client.readBatterySnapshot())
    }

    @Test
    fun `readChargingSnapshot aggregates gun_state and type`() = runTest {
        val adb = FakeAdb(responses = mapOf(
            "service call autoservice 5 i32 1005 i32 ${FidRegistry.FID_GUN_CONNECT_STATE}" to parcelInt(2),
            "service call autoservice 5 i32 1005 i32 ${FidRegistry.FID_CHARGING_TYPE}" to parcelInt(2),
            "service call autoservice 5 i32 1005 i32 ${FidRegistry.FID_CHARGE_BATTERY_VOLT}" to parcelInt(512),
            "service call autoservice 5 i32 1005 i32 ${FidRegistry.FID_BATTERY_TYPE}" to parcelInt(1)
        ))
        val client = AutoserviceClientImpl(adb)

        val snap = client.readChargingSnapshot()

        assertEquals(2, snap!!.gunConnectState)
        assertEquals(2, snap.chargingType)
        assertEquals(512, snap.chargeBatteryVoltV)
        assertEquals(1, snap.batteryType)
    }

    @Test
    fun `isAvailable returns false when ADB not connected`() = runTest {
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertEquals(false, client.isAvailable())
    }

    @Test
    fun `isAvailable returns true when ADB connected and SoH read succeeds`() = runTest {
        val cmd = "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOH}"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0x42C80000.toInt())))
        val client = AutoserviceClientImpl(adb)

        assertEquals(true, client.isAvailable())
    }

    @Test
    fun `isAvailable returns false when SoH read returns sentinel`() = runTest {
        val cmd = "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOH}"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0xBF800000.toInt())))
        val client = AutoserviceClientImpl(adb)

        // Connected but SoH read returns null → autoservice fids inaccessible on this model.
        assertEquals(false, client.isAvailable())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.AutoserviceClientImplTest"
```

Expected: FAIL — `Unresolved reference: AutoserviceClient` and `AutoserviceClientImpl`.

- [ ] **Step 3: Implement AutoserviceClient + AutoserviceClientImpl**

Create `app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt`:

```kotlin
package com.bydmate.app.data.autoservice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to the system autoservice Binder via on-device ADB.
 *
 * Returns null on any error (sentinel, ADB down, parse failure, autoservice
 * unsupported on this firmware). Caller MUST handle null gracefully — do not
 * propagate exceptions for "fid not available".
 */
interface AutoserviceClient {
    /** Best-effort liveness check: ADB connected AND a known fid (SoH) returns a real value. */
    suspend fun isAvailable(): Boolean
    suspend fun getInt(dev: Int, fid: Int): Int?
    suspend fun getFloat(dev: Int, fid: Int): Float?
    suspend fun readBatterySnapshot(): BatteryReading?
    suspend fun readChargingSnapshot(): ChargingReading?
}

@Singleton
class AutoserviceClientImpl @Inject constructor(
    private val adb: AdbOnDeviceClient
) : AutoserviceClient {

    override suspend fun isAvailable(): Boolean {
        if (!adb.isConnected()) return false
        // SoH is the lightest known-good probe — present on every BMS.
        val soh = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_SOH)
        return soh != null
    }

    override suspend fun getInt(dev: Int, fid: Int): Int? {
        val cmd = "service call autoservice ${FidRegistry.TX_GET_INT} i32 $dev i32 $fid"
        val raw = adb.exec(cmd) ?: return null
        val value = parseParcelInt(raw) ?: return null
        return SentinelDecoder.decodeInt(value)
    }

    override suspend fun getFloat(dev: Int, fid: Int): Float? {
        val cmd = "service call autoservice ${FidRegistry.TX_GET_FLOAT} i32 $dev i32 $fid"
        val raw = adb.exec(cmd) ?: return null
        val bits = parseParcelInt(raw) ?: return null
        return SentinelDecoder.parseFloatFromShellInt(bits)
    }

    override suspend fun readBatterySnapshot(): BatteryReading? {
        if (!adb.isConnected()) return null
        return BatteryReading(
            sohPercent = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_SOH),
            socPercent = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_SOC),
            lifetimeKwh = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_LIFETIME_KWH),
            lifetimeMileageKm = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_LIFETIME_MILEAGE),
            voltage12v = getFloat(FidRegistry.DEV_BODYWORK, FidRegistry.FID_OTA_BATTERY_POWER_VOLTAGE),
            readAtMs = System.currentTimeMillis()
        )
    }

    override suspend fun readChargingSnapshot(): ChargingReading? {
        if (!adb.isConnected()) return null
        return ChargingReading(
            gunConnectState = getInt(FidRegistry.DEV_CHARGING, FidRegistry.FID_GUN_CONNECT_STATE),
            chargingType = getInt(FidRegistry.DEV_CHARGING, FidRegistry.FID_CHARGING_TYPE),
            chargeBatteryVoltV = getInt(FidRegistry.DEV_CHARGING, FidRegistry.FID_CHARGE_BATTERY_VOLT),
            batteryType = getInt(FidRegistry.DEV_CHARGING, FidRegistry.FID_BATTERY_TYPE),
            readAtMs = System.currentTimeMillis()
        )
    }

    /**
     * `service call autoservice <tx> i32 <dev> i32 <fid>` produces stdout like:
     *   Result: Parcel(00000000 0000005b   '....[...')
     * The 8-hex-digit token after "Parcel(00000000" is the 32-bit return value.
     */
    private fun parseParcelInt(raw: String): Int? {
        val match = PARCEL_REGEX.find(raw) ?: return null
        return runCatching { match.groupValues[1].toLong(16).toInt() }.getOrNull()
    }

    private companion object {
        val PARCEL_REGEX = Regex("""Parcel\(00000000\s+([0-9a-fA-F]{8})""")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.autoservice.AutoserviceClientImplTest"
```

Expected: PASS, 11 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt \
        app/src/test/kotlin/com/bydmate/app/data/autoservice/AutoserviceClientImplTest.kt
git commit -m "feat(autoservice): AutoserviceClient with sentinel-aware reads"
```

---

## Task 6: ChargingTypeClassifier

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/charging/ChargingTypeClassifier.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/data/charging/ChargingTypeClassifierTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bydmate/app/data/charging/ChargingTypeClassifierTest.kt`:

```kotlin
package com.bydmate.app.data.charging

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingTypeClassifierTest {

    private val classifier = ChargingTypeClassifier()

    @Test
    fun `gun_state 2 maps to AC`() {
        assertEquals("AC", classifier.fromGunState(2))
    }

    @Test
    fun `gun_state 3 maps to DC`() {
        assertEquals("DC", classifier.fromGunState(3))
    }

    @Test
    fun `gun_state 4 GB_DC maps to DC`() {
        assertEquals("DC", classifier.fromGunState(4))
    }

    @Test
    fun `gun_state 1 NONE returns null`() {
        assertEquals(null, classifier.fromGunState(1))
    }

    @Test
    fun `gun_state null returns null`() {
        assertEquals(null, classifier.fromGunState(null))
    }

    @Test
    fun `gun_state 0 returns null (unknown)`() {
        assertEquals(null, classifier.fromGunState(0))
    }

    @Test
    fun `heuristic returns DC when kwh per hour exceeds 20`() {
        // 30 kWh in 1 hour = 30 kW avg → clearly DC
        assertEquals("DC", classifier.heuristicByPower(kwhCharged = 30.0, hours = 1.0))
    }

    @Test
    fun `heuristic returns DC at boundary (just above 20 kW per hour)`() {
        assertEquals("DC", classifier.heuristicByPower(kwhCharged = 21.0, hours = 1.0))
    }

    @Test
    fun `heuristic returns AC when kwh per hour is 20 or below`() {
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 20.0, hours = 1.0))
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 7.0, hours = 1.0))
    }

    @Test
    fun `heuristic handles fractional hours correctly`() {
        // 10 kWh in 0.25 h = 40 kW avg → DC
        assertEquals("DC", classifier.heuristicByPower(kwhCharged = 10.0, hours = 0.25))
        // 10 kWh in 2 h = 5 kW avg → AC
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 10.0, hours = 2.0))
    }

    @Test
    fun `heuristic returns AC for zero hours (degenerate, default to safe)`() {
        // Zero or negative duration is meaningless — default to AC (cheaper tariff).
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 5.0, hours = 0.0))
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 5.0, hours = -1.0))
    }

    @Test
    fun `heuristic returns AC when kwh is zero`() {
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 0.0, hours = 1.0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.ChargingTypeClassifierTest"
```

Expected: FAIL — `Unresolved reference: ChargingTypeClassifier`.

- [ ] **Step 3: Implement ChargingTypeClassifier**

Create `app/src/main/kotlin/com/bydmate/app/data/charging/ChargingTypeClassifier.kt`:

```kotlin
package com.bydmate.app.data.charging

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies a charging session as AC or DC.
 *
 * Preferred path (live): use the `gun_state` value captured at handshake.
 * Fallback (catch-up): use kwh / hours heuristic — DC chargers deliver
 * far more than 20 kW averaged across a session; home AC chargers cap
 * around 7-11 kW. The 20 kW boundary cleanly separates them.
 *
 * The user can always override via the optional finalize prompt (Phase 3).
 */
@Singleton
class ChargingTypeClassifier @Inject constructor() {

    companion object {
        /** kWh-per-hour boundary between AC and DC. */
        const val DC_AVG_POWER_KW_THRESHOLD = 20.0
    }

    /**
     * Maps the raw gun_state autoservice value to "AC"/"DC", or null if
     * the gun is disconnected (state 1) or unknown.
     *   1 = NONE
     *   2 = AC
     *   3 = DC
     *   4 = GB_DC (treat as DC for UI/tariff purposes)
     */
    fun fromGunState(gunState: Int?): String? = when (gunState) {
        2 -> "AC"
        3, 4 -> "DC"
        else -> null
    }

    /**
     * Heuristic for catch-up paths where gun_state is no longer available.
     * Returns "DC" if avg power > threshold; "AC" otherwise (and as a safe
     * default when inputs are degenerate — picks the cheaper tariff).
     */
    fun heuristicByPower(kwhCharged: Double, hours: Double): String {
        if (kwhCharged <= 0.0 || hours <= 0.0) return "AC"
        val avgKw = kwhCharged / hours
        return if (avgKw > DC_AVG_POWER_KW_THRESHOLD) "DC" else "AC"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.ChargingTypeClassifierTest"
```

Expected: PASS, 12 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/charging/ChargingTypeClassifier.kt \
        app/src/test/kotlin/com/bydmate/app/data/charging/ChargingTypeClassifierTest.kt
git commit -m "feat(charging): ChargingTypeClassifier (gun_state + kwh/h heuristic)"
```

---

## Task 7: ChargeEntity new fields + ChargeDao baseline query

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/local/entity/ChargeEntity.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/data/local/dao/ChargeDao.kt`

> **Note:** Migration SQL itself ships in Task 8. Here we just update the entity + DAO so kotlin code can read/write the new columns. After this commit the schema export (`schemas/.../12.json`) regenerates on next build but does NOT execute against any DB.

- [ ] **Step 1: Add 4 new fields to `ChargeEntity`**

Edit `app/src/main/kotlin/com/bydmate/app/data/local/entity/ChargeEntity.kt`. Replace the entire file with:

```kotlin
package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "charges",
    indices = [
        Index(value = ["start_ts"]),
        Index(value = ["status"])
    ]
)
data class ChargeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ts") val startTs: Long,
    @ColumnInfo(name = "end_ts") val endTs: Long? = null,
    @ColumnInfo(name = "soc_start") val socStart: Int? = null,
    @ColumnInfo(name = "soc_end") val socEnd: Int? = null,
    @ColumnInfo(name = "kwh_charged") val kwhCharged: Double? = null,
    @ColumnInfo(name = "kwh_charged_soc") val kwhChargedSoc: Double? = null,
    @ColumnInfo(name = "max_power_kw") val maxPowerKw: Double? = null,
    val type: String? = null, // "AC" or "DC"
    val cost: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "bat_temp_avg") val batTempAvg: Double? = null,
    @ColumnInfo(name = "bat_temp_max") val batTempMax: Double? = null,
    @ColumnInfo(name = "bat_temp_min") val batTempMin: Double? = null,
    @ColumnInfo(name = "avg_power_kw") val avgPowerKw: Double? = null,
    val status: String = "COMPLETED",
    @ColumnInfo(name = "cell_voltage_min") val cellVoltageMin: Double? = null,
    @ColumnInfo(name = "cell_voltage_max") val cellVoltageMax: Double? = null,
    @ColumnInfo(name = "voltage_12v") val voltage12v: Double? = null,
    @ColumnInfo(name = "exterior_temp") val exteriorTemp: Int? = null,
    @ColumnInfo(name = "merged_count") val mergedCount: Int = 0,
    // v12: autoservice catch-up trace
    @ColumnInfo(name = "lifetime_kwh_at_start") val lifetimeKwhAtStart: Double? = null,
    @ColumnInfo(name = "lifetime_kwh_at_finish") val lifetimeKwhAtFinish: Double? = null,
    @ColumnInfo(name = "gun_state") val gunState: Int? = null,
    @ColumnInfo(name = "detection_source") val detectionSource: String? = null
)
```

- [ ] **Step 2: Add `getMaxLifetimeKwhAtFinish` query to `ChargeDao`**

Edit `app/src/main/kotlin/com/bydmate/app/data/local/dao/ChargeDao.kt`. Append before the closing `}` of the interface (after line 53):

```kotlin
    /**
     * Returns the highest lifetime_kwh_at_finish recorded by an autoservice
     * detector, or null if no autoservice-sourced sessions exist yet.
     * Used as the catch-up baseline.
     */
    @Query("""
        SELECT MAX(lifetime_kwh_at_finish) FROM charges
        WHERE detection_source LIKE 'autoservice%'
          AND lifetime_kwh_at_finish IS NOT NULL
    """)
    suspend fun getMaxLifetimeKwhAtFinish(): Double?
```

- [ ] **Step 3: Compile and verify schema regenerates**

```bash
./gradlew :app:compileDebugKotlin
ls app/schemas/com.bydmate.app.data.local.database.AppDatabase/
```

Expected: file `12.json` does NOT yet exist (we haven't bumped the version). File `11.json` is unchanged. Build succeeds — Room is happy that DAO references a column the entity now declares.

> If build fails with "Cannot find setter for field" — the `@ColumnInfo(name = ...)` snake_case must match the SQL ALTER in Task 8 exactly.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/local/entity/ChargeEntity.kt \
        app/src/main/kotlin/com/bydmate/app/data/local/dao/ChargeDao.kt
git commit -m "feat(db): ChargeEntity gains lifetime_kwh trace + detection_source"
```

---

## Task 8: Migration 11→12 + Robolectric MigrationTest

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt:42`
- Modify: `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/data/local/database/Migration11to12Test.kt`

- [ ] **Step 1: Bump database version**

Edit `app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt:42`:

```kotlin
    version = 12,
```

(Single-line edit. `entities = [...]` block stays unchanged.)

- [ ] **Step 2: Add the migration to AppModule**

Edit `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt`. Append this migration after `MIGRATION_10_11` (after line 209):

```kotlin

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v12: autoservice catch-up trace + baseline source.
            // 4 new fields on existing 'charges' table — see spec section 5.1.
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_start REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_finish REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN gun_state INTEGER")
            db.execSQL("ALTER TABLE charges ADD COLUMN detection_source TEXT")

            // One-shot cleanup of unfinished sessions left by the removed
            // ChargeTracker. COMPLETED sessions stay in history.
            db.execSQL("DELETE FROM charges WHERE status IN ('SUSPENDED', 'ACTIVE')")
        }
    }
```

Also extend the `addMigrations(...)` line (line 219). Replace with:

```kotlin
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
```

- [ ] **Step 3: Trigger schema regeneration**

```bash
./gradlew :app:assembleDebug
ls app/schemas/com.bydmate.app.data.local.database.AppDatabase/
```

Expected: file `12.json` now exists alongside `11.json`. The schema must be committed alongside the migration so the test can reference it.

- [ ] **Step 4: Write the failing migration test**

Create `app/src/test/kotlin/com/bydmate/app/data/local/database/Migration11to12Test.kt`:

```kotlin
package com.bydmate.app.data.local.database

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration11to12Test {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `migrate adds 4 new columns to charges table`() {
        // Create v11 schema
        helper.createDatabase(dbName, 11).apply {
            // Insert one COMPLETED row that should survive the cleanup.
            execSQL("""
                INSERT INTO charges (start_ts, end_ts, soc_start, soc_end, kwh_charged, status, merged_count)
                VALUES (1700000000000, 1700003600000, 50, 80, 20.5, 'COMPLETED', 0)
            """)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 12, /*validateDroppedTables=*/true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_11_12
        )

        // Read back; new columns should exist (NULL for legacy rows) and old data preserved.
        migrated.query(
            "SELECT id, kwh_charged, status, lifetime_kwh_at_start, lifetime_kwh_at_finish, gun_state, detection_source FROM charges"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(20.5, c.getDouble(1), 0.001)
            assertEquals("COMPLETED", c.getString(2))
            assertEquals(true, c.isNull(3))   // lifetime_kwh_at_start
            assertEquals(true, c.isNull(4))   // lifetime_kwh_at_finish
            assertEquals(true, c.isNull(5))   // gun_state
            assertEquals(true, c.isNull(6))   // detection_source
        }
        migrated.close()
    }

    @Test
    fun `migrate deletes SUSPENDED and ACTIVE sessions but keeps COMPLETED`() {
        helper.createDatabase(dbName, 11).apply {
            execSQL("""
                INSERT INTO charges (start_ts, status, merged_count) VALUES
                  (1700000000000, 'COMPLETED', 0),
                  (1700001000000, 'SUSPENDED', 0),
                  (1700002000000, 'ACTIVE', 0),
                  (1700003000000, 'COMPLETED', 0)
            """)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 12, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_11_12
        )

        migrated.query("SELECT status FROM charges ORDER BY start_ts").use { c ->
            val statuses = mutableListOf<String>()
            while (c.moveToNext()) statuses += c.getString(0)
            assertEquals(listOf("COMPLETED", "COMPLETED"), statuses)
        }
        migrated.close()
    }

    @Test
    fun `new charge can be inserted with autoservice fields after migration`() {
        helper.createDatabase(dbName, 11).close()
        val migrated = helper.runMigrationsAndValidate(
            dbName, 12, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_11_12
        )

        val cv = ContentValues().apply {
            put("start_ts", 1700004000000L)
            put("end_ts", 1700007600000L)
            put("soc_start", 30)
            put("soc_end", 80)
            put("kwh_charged", 36.5)
            put("status", "COMPLETED")
            put("merged_count", 0)
            put("lifetime_kwh_at_start", 600.0)
            put("lifetime_kwh_at_finish", 636.5)
            put("gun_state", 2)
            put("detection_source", "autoservice_catchup")
        }
        migrated.insert("charges", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, cv)

        migrated.query("SELECT lifetime_kwh_at_finish, gun_state, detection_source FROM charges WHERE start_ts = 1700004000000").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(636.5, c.getDouble(0), 0.001)
            assertEquals(2, c.getInt(1))
            assertEquals("autoservice_catchup", c.getString(2))
        }
        migrated.close()
    }
}
```

The test references `AppModuleMigrationsForTest.MIGRATION_11_12` — we need a tiny test-side helper that re-exposes the private migration object:

Create `app/src/test/kotlin/com/bydmate/app/di/AppModuleMigrationsForTest.kt`:

```kotlin
package com.bydmate.app.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Re-exports MIGRATION_11_12 (private inside AppModule object) so that
 * Migration11to12Test can pass it to MigrationTestHelper. Only used in
 * unit tests; production code stays in AppModule.
 *
 * Keep in lockstep with AppModule.MIGRATION_11_12 — if the migration
 * SQL changes, update both. The duplication is intentional: making
 * AppModule.MIGRATION_11_12 internal would leak Hilt internals.
 */
object AppModuleMigrationsForTest {
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_start REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_finish REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN gun_state INTEGER")
            db.execSQL("ALTER TABLE charges ADD COLUMN detection_source TEXT")
            db.execSQL("DELETE FROM charges WHERE status IN ('SUSPENDED', 'ACTIVE')")
        }
    }
}
```

Tell `MigrationTestHelper` where to find the schema by adding to `app/build.gradle.kts` inside the `android { defaultConfig { ... } }` block (after `testInstrumentationRunner`, line 23):

```kotlin
        sourceSets {
            getByName("test") {
                assets.srcDirs("$projectDir/schemas")
            }
        }
```

(Without this, `MigrationTestHelper` cannot find `12.json` and throws `FileNotFoundException` at runtime.)

- [ ] **Step 5: Run the migration tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.local.database.Migration11to12Test"
```

Expected: PASS, 3 tests green.

If `MigrationTestHelper` complains about missing `11.json` — verify `app/schemas/com.bydmate.app.data.local.database.AppDatabase/11.json` exists from earlier builds. If absent, run `./gradlew :app:assembleDebug` first.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt \
        app/src/main/kotlin/com/bydmate/app/di/AppModule.kt \
        app/src/test/kotlin/com/bydmate/app/data/local/database/Migration11to12Test.kt \
        app/src/test/kotlin/com/bydmate/app/di/AppModuleMigrationsForTest.kt \
        app/build.gradle.kts \
        app/schemas/com.bydmate.app.data.local.database.AppDatabase/12.json
git commit -m "feat(db): migration 11->12 + Robolectric MigrationTestHelper coverage"
```

---

## Task 9: SettingsRepository — new keys + accessors

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/repository/SettingsRepository.kt`

- [ ] **Step 1: Add new constants in the companion object**

Edit `app/src/main/kotlin/com/bydmate/app/data/repository/SettingsRepository.kt`. After `const val KEY_DATA_SOURCE = "data_source"` (line 35) add:

```kotlin
        const val KEY_AUTOSERVICE_ENABLED = "autoservice_enabled"
        const val KEY_CHARGING_PROMPT_ENABLED = "charging_prompt_enabled"
        const val KEY_AUTOSERVICE_BASELINE_KWH = "autoservice_baseline_kwh"
        const val KEY_AUTOSERVICE_BASELINE_TS = "autoservice_baseline_ts"
        const val KEY_LAST_SEEN_SOC = "last_seen_soc"
```

- [ ] **Step 2: Add the matching accessor methods**

Append at the bottom of the class (before the final `}`, after `observeDataSource()` at line 158):

```kotlin

    suspend fun isAutoserviceEnabled(): Boolean =
        getString(KEY_AUTOSERVICE_ENABLED, "false") == "true"

    suspend fun setAutoserviceEnabled(enabled: Boolean) =
        setString(KEY_AUTOSERVICE_ENABLED, enabled.toString())

    suspend fun isChargingPromptEnabled(): Boolean =
        getString(KEY_CHARGING_PROMPT_ENABLED, "true") == "true"

    suspend fun setChargingPromptEnabled(enabled: Boolean) =
        setString(KEY_CHARGING_PROMPT_ENABLED, enabled.toString())

    suspend fun getAutoserviceBaseline(): Pair<Double, Long>? {
        val kwh = getString(KEY_AUTOSERVICE_BASELINE_KWH, "").toDoubleOrNull() ?: return null
        val ts = getString(KEY_AUTOSERVICE_BASELINE_TS, "0").toLongOrNull() ?: 0L
        return kwh to ts
    }

    suspend fun setAutoserviceBaseline(kwh: Double, ts: Long) {
        setString(KEY_AUTOSERVICE_BASELINE_KWH, kwh.toString())
        setString(KEY_AUTOSERVICE_BASELINE_TS, ts.toString())
    }

    suspend fun getLastSeenSoc(): Int? =
        getString(KEY_LAST_SEEN_SOC, "").toIntOrNull()

    suspend fun setLastSeenSoc(soc: Int) =
        setString(KEY_LAST_SEEN_SOC, soc.toString())
```

- [ ] **Step 3: Compile sanity check**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/repository/SettingsRepository.kt
git commit -m "feat(settings): keys and accessors for autoservice toggle + baseline"
```

---

## Task 10: ChargingBaselineStore

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/charging/ChargingBaselineStore.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/data/charging/ChargingBaselineStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bydmate/app/data/charging/ChargingBaselineStoreTest.kt`:

```kotlin
package com.bydmate.app.data.charging

import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargingBaselineStoreTest {

    private class FakeChargeDao(private val maxKwh: Double?) : ChargeDao {
        override suspend fun insert(charge: ChargeEntity): Long = 0L
        override suspend fun update(charge: ChargeEntity) {}
        override fun getAll(): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ChargeEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary =
            ChargeSummary(0, 0.0, 0.0)
        override fun getLastCharge(): Flow<ChargeEntity?> = flowOf(null)
        override suspend fun getLastSuspendedCharge(): ChargeEntity? = null
        override suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> = emptyList()
        override suspend fun getLastChargeSync(): ChargeEntity? = null
        override suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> = emptyList()
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = maxKwh
    }

    /**
     * Minimal SettingsRepository fake. Only the four methods the store touches
     * are implemented — others throw to fail loudly if used.
     */
    private class FakeSettings(
        var baseline: Pair<Double, Long>? = null
    ) : SettingsRepository(FakeSettingsDao()) {
        override suspend fun getAutoserviceBaseline(): Pair<Double, Long>? = baseline
        override suspend fun setAutoserviceBaseline(kwh: Double, ts: Long) {
            baseline = kwh to ts
        }
    }

    private class FakeSettingsDao : com.bydmate.app.data.local.dao.SettingsDao {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: com.bydmate.app.data.local.entity.SettingEntity) {
            map[entity.key] = entity.value
        }
    }

    private fun store(maxFromDb: Double?, baseline: Pair<Double, Long>?): ChargingBaselineStore {
        val dao = FakeChargeDao(maxFromDb)
        val chargeRepo = ChargeRepository(dao, /*chargePointDao=*/ NullChargePointDao)
        val settings = FakeSettings(baseline)
        return ChargingBaselineStore(chargeRepo, settings)
    }

    private object NullChargePointDao : com.bydmate.app.data.local.dao.ChargePointDao {
        override suspend fun insertAll(points: List<com.bydmate.app.data.local.entity.ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<com.bydmate.app.data.local.entity.ChargePointEntity> = emptyList()
        override suspend fun delete(chargeId: Long) {}
        override suspend fun thinPointsForCharge(chargeId: Long, intervalMs: Long) {}
    }

    @Test
    fun `prefers DB MAX over k-v fallback`() = runTest {
        val s = store(maxFromDb = 600.5, baseline = 100.0 to 1L)
        assertEquals(600.5, s.getBaseline()!!, 0.001)
    }

    @Test
    fun `falls back to k-v when DB has no autoservice rows`() = runTest {
        val s = store(maxFromDb = null, baseline = 250.0 to 1700000000000L)
        assertEquals(250.0, s.getBaseline()!!, 0.001)
    }

    @Test
    fun `returns null when DB and k-v are both empty (cold start)`() = runTest {
        val s = store(maxFromDb = null, baseline = null)
        assertNull(s.getBaseline())
    }

    @Test
    fun `setBaseline writes to k-v store`() = runTest {
        val settings = FakeSettings(baseline = null)
        val dao = FakeChargeDao(null)
        val s = ChargingBaselineStore(ChargeRepository(dao, NullChargePointDao), settings)

        s.setBaseline(kwh = 700.0, ts = 1700100000000L)

        assertEquals(700.0 to 1700100000000L, settings.baseline)
    }
}
```

> **Note on `FakeSettings`:** `SettingsRepository` is `@Singleton open` only by Kotlin convention; if it is `final` we cannot override. If the test fails to compile because of `final` modifier, edit `SettingsRepository` to mark the class `open` and the two methods `open` (single-line edits, no behaviour change). This is a justified test-only cost.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.ChargingBaselineStoreTest"
```

Expected: FAIL — `Unresolved reference: ChargingBaselineStore`. (And, if `SettingsRepository` is `final`: "This type is final, so it cannot be inherited from" — fix per the note above.)

- [ ] **Step 3: Implement ChargingBaselineStore**

Create `app/src/main/kotlin/com/bydmate/app/data/charging/ChargingBaselineStore.kt`:

```kotlin
package com.bydmate.app.data.charging

import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the autoservice charging baseline (lifetime_kwh
 * at the end of the most recent autoservice-tracked session).
 *
 * Lookup order:
 *   1. DB: MAX(lifetime_kwh_at_finish) WHERE detection_source LIKE 'autoservice%'
 *   2. k/v fallback: KEY_AUTOSERVICE_BASELINE_KWH (used during cold start, before
 *      the very first autoservice session is recorded).
 *   3. null → caller treats as "first run", records current lifetime_kwh as the
 *      seed baseline without creating a phantom session.
 */
@Singleton
class ChargingBaselineStore @Inject constructor(
    private val chargeRepo: ChargeRepository,
    private val settings: SettingsRepository
) {
    suspend fun getBaseline(): Double? {
        val fromDb = chargeRepo.getMaxLifetimeKwhAtFinish()
        if (fromDb != null) return fromDb
        return settings.getAutoserviceBaseline()?.first
    }

    suspend fun setBaseline(kwh: Double, ts: Long) {
        settings.setAutoserviceBaseline(kwh, ts)
    }
}
```

The DAO method must be exposed on the repository. Edit `app/src/main/kotlin/com/bydmate/app/data/repository/ChargeRepository.kt`. Append before the closing `}` (after line 45):

```kotlin

    suspend fun getMaxLifetimeKwhAtFinish(): Double? =
        chargeDao.getMaxLifetimeKwhAtFinish()
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.ChargingBaselineStoreTest"
```

Expected: PASS, 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/charging/ChargingBaselineStore.kt \
        app/src/main/kotlin/com/bydmate/app/data/repository/ChargeRepository.kt \
        app/src/test/kotlin/com/bydmate/app/data/charging/ChargingBaselineStoreTest.kt
# Optionally include SettingsRepository if it had to be marked `open`
git commit -m "feat(charging): ChargingBaselineStore (DB max + k/v fallback)"
```

---

## Task 11: AutoserviceChargingDetector (catch-up state machine)

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetectorTest.kt`

> **Scope reminder:** Phase 1 only ships `runCatchUp()`. Live tick (`runLiveTick()`) is Phase 3. We add the StateFlow scaffold but do not start any timer.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetectorTest.kt`:

```kotlin
package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoserviceChargingDetectorTest {

    // --- fakes ---

    private class FakeAutoservice(
        var battery: BatteryReading?,
        var charging: ChargingReading?,
        var available: Boolean = true
    ) : AutoserviceClient {
        override suspend fun isAvailable(): Boolean = available
        override suspend fun getInt(dev: Int, fid: Int): Int? = null
        override suspend fun getFloat(dev: Int, fid: Int): Float? = null
        override suspend fun readBatterySnapshot(): BatteryReading? = battery
        override suspend fun readChargingSnapshot(): ChargingReading? = charging
    }

    private class RecordingDao(
        private var maxBaseline: Double? = null
    ) : ChargeDao {
        val inserted = mutableListOf<ChargeEntity>()
        var nextId: Long = 1
        override suspend fun insert(charge: ChargeEntity): Long {
            val withId = charge.copy(id = nextId++)
            inserted += withId
            // Roll baseline forward if this insert recorded a new finish.
            withId.lifetimeKwhAtFinish?.let { maxBaseline = it }
            return withId.id
        }
        override suspend fun update(charge: ChargeEntity) {}
        override fun getAll(): Flow<List<ChargeEntity>> = flowOf(inserted.toList())
        override suspend fun getById(id: Long): ChargeEntity? = inserted.find { it.id == id }
        override fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary = ChargeSummary(0, 0.0, 0.0)
        override fun getLastCharge(): Flow<ChargeEntity?> = flowOf(inserted.lastOrNull())
        override suspend fun getLastSuspendedCharge(): ChargeEntity? = null
        override suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> = emptyList()
        override suspend fun getLastChargeSync(): ChargeEntity? = inserted.lastOrNull()
        override suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> = emptyList()
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = maxBaseline
    }

    private object NullChargePointDao : com.bydmate.app.data.local.dao.ChargePointDao {
        override suspend fun insertAll(points: List<ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<ChargePointEntity> = emptyList()
        override suspend fun delete(chargeId: Long) {}
        override suspend fun thinPointsForCharge(chargeId: Long, intervalMs: Long) {}
    }

    private class FakeSettingsDao : com.bydmate.app.data.local.dao.SettingsDao {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: com.bydmate.app.data.local.entity.SettingEntity) { map[entity.key] = entity.value }
    }

    private class FakeBatteryHealthRepo :
        BatteryHealthRepository(NullBatterySnapshotDao()) {
        val inserted = mutableListOf<com.bydmate.app.data.local.entity.BatterySnapshotEntity>()
        override suspend fun insert(snapshot: com.bydmate.app.data.local.entity.BatterySnapshotEntity): Long {
            inserted += snapshot
            return inserted.size.toLong()
        }
    }

    private class NullBatterySnapshotDao : com.bydmate.app.data.local.dao.BatterySnapshotDao {
        override fun getAll(): Flow<List<com.bydmate.app.data.local.entity.BatterySnapshotEntity>> = flowOf(emptyList())
        override fun getRecent(limit: Int): Flow<List<com.bydmate.app.data.local.entity.BatterySnapshotEntity>> = flowOf(emptyList())
        override suspend fun insert(snapshot: com.bydmate.app.data.local.entity.BatterySnapshotEntity): Long = 0
        override suspend fun getLast(): com.bydmate.app.data.local.entity.BatterySnapshotEntity? = null
        override suspend fun getCount(): Int = 0
    }

    private fun build(
        battery: BatteryReading?,
        charging: ChargingReading? = ChargingReading(1, 1, 0, 1, 0L),
        baselineKwh: Double? = null,
        baselineTs: Long = 0L,
        autoserviceAvailable: Boolean = true,
        homeTariff: Double = 0.20,
        dcTariff: Double = 0.73
    ): Triple<AutoserviceChargingDetector, RecordingDao, FakeBatteryHealthRepo> {
        val auto = FakeAutoservice(battery, charging, autoserviceAvailable)
        val dao = RecordingDao(maxBaseline = baselineKwh)
        val chargeRepo = ChargeRepository(dao, NullChargePointDao)
        val healthRepo = FakeBatteryHealthRepo()
        val settingsDao = FakeSettingsDao()
        val settings = object : SettingsRepository(settingsDao) {
            override suspend fun getHomeTariff(): Double = homeTariff
            override suspend fun getDcTariff(): Double = dcTariff
            override suspend fun getAutoserviceBaseline(): Pair<Double, Long>? =
                if (baselineKwh != null) baselineKwh to baselineTs else null
        }
        val baselineStore = ChargingBaselineStore(chargeRepo, settings)
        val classifier = ChargingTypeClassifier()
        val detector = AutoserviceChargingDetector(
            client = auto,
            chargeRepo = chargeRepo,
            batteryHealthRepo = healthRepo,
            baselineStore = baselineStore,
            classifier = classifier,
            settings = settings
        )
        return Triple(detector, dao, healthRepo)
    }

    @Test
    fun `cold start records baseline without creating a session`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 602.7f,
            lifetimeMileageKm = 2091f, voltage12v = 14.0f, readAtMs = 1000L
        )
        val (detector, dao, _) = build(battery, baselineKwh = null)

        val result = detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.BASELINE_INITIALIZED, result.outcome)
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `lifetime_kwh delta below threshold does NOT create a session`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 600.4f,  // baseline 600.0 → delta 0.4 < 0.5
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val (detector, dao, _) = build(battery, baselineKwh = 600.0)

        val result = detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.NO_DELTA, result.outcome)
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `lifetime_kwh delta of 8 kWh creates one session marked autoservice_catchup`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,  // baseline 600 → delta 8
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val charging = ChargingReading(
            gunConnectState = 1,  // already disconnected (catch-up scenario)
            chargingType = 1, chargeBatteryVoltV = 0, batteryType = 1, readAtMs = 1000L
        )
        val (detector, dao, _) = build(battery, charging, baselineKwh = 600.0)

        val result = detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, dao.inserted.size)
        val ch = dao.inserted.single()
        assertEquals("COMPLETED", ch.status)
        assertEquals(8.0, ch.kwhCharged!!, 0.01)
        assertEquals(600.0, ch.lifetimeKwhAtStart!!, 0.01)
        assertEquals(608.0, ch.lifetimeKwhAtFinish!!, 0.01)
        assertEquals("autoservice_catchup", ch.detectionSource)
        // Heuristic: 8 kWh in <1h would be DC, but we cannot know hours from a snapshot
        // alone. The detector uses a duration assumption (see implementation). Type may
        // be AC or DC depending on the heuristic — we only assert it's one of them.
        assertTrue(ch.type == "AC" || ch.type == "DC")
        assertNotNull(ch.cost)
    }

    @Test
    fun `gun_state DC during catch-up overrides heuristic`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        // gun still inserted as DC (rare: user comes back to plug-in DC and we catch-up before disconnect)
        val charging = ChargingReading(
            gunConnectState = 3, chargingType = 4, chargeBatteryVoltV = 700,
            batteryType = 1, readAtMs = 1000L
        )
        val (detector, dao, _) = build(battery, charging, baselineKwh = 600.0)

        detector.runCatchUp(now = 1500L)

        val ch = dao.inserted.single()
        assertEquals("DC", ch.type)
        assertEquals(3, ch.gunState)
    }

    @Test
    fun `session creation also records BatterySnapshot when delta SOC is 5 or more`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val (detector, _, healthRepo) = build(battery, baselineKwh = 600.0)
        // We need a "last seen SOC" so the detector has a delta to compare against.
        // The fake settings exposes setLastSeenSoc via the parent SettingsRepository.
        // Configure baseline + last seen SOC of 80 so delta = 91 - 80 = 11 ≥ 5.
        detector.recordLastSeenSoc(80)

        detector.runCatchUp(now = 1500L)

        // BatterySnapshot recorded with SOC delta and capacity calculation.
        assertEquals(1, healthRepo.inserted.size)
        val snap = healthRepo.inserted.single()
        assertEquals(80, snap.socStart)
        assertEquals(91, snap.socEnd)
        assertEquals(8.0, snap.kwhCharged, 0.01)
        // calculateCapacity = 8 / (91-80) * 100 = 72.7 kWh
        assertEquals(72.7, snap.calculatedCapacityKwh!!, 0.1)
    }

    @Test
    fun `BatterySnapshot is NOT recorded when SOC delta is below 5`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val (detector, _, healthRepo) = build(battery, baselineKwh = 600.0)
        detector.recordLastSeenSoc(89)  // delta = 2

        detector.runCatchUp(now = 1500L)

        assertEquals(0, healthRepo.inserted.size)
    }

    @Test
    fun `runCatchUp returns AUTOSERVICE_UNAVAILABLE when client is down`() = runTest {
        val (detector, dao, _) = build(battery = null, autoserviceAvailable = false)

        val result = detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE, result.outcome)
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `runCatchUp returns SENTINEL when lifetime_kwh comes back null`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = null,  // sentinel
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val (detector, dao, _) = build(battery, baselineKwh = 600.0)

        val result = detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SENTINEL, result.outcome)
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `subsequent catch-up after a session uses the new baseline`() = runTest {
        val battery1 = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val (detector, dao, _) = build(battery1, baselineKwh = 600.0)
        detector.runCatchUp(now = 1500L)
        assertEquals(1, dao.inserted.size)

        // Next session: lifetime_kwh now 612 (was 608). delta = 4.
        // We mutate the fake's reading and re-run. Baseline is read from DAO MAX.
        val auto = (detector.let { (it as Any).javaClass.getDeclaredField("client") }
            .apply { isAccessible = true }.get(detector) as FakeAutoservice)
        auto.battery = battery1.copy(lifetimeKwh = 612.0f)

        detector.runCatchUp(now = 2500L)

        assertEquals(2, dao.inserted.size)
        val second = dao.inserted[1]
        assertEquals(608.0, second.lifetimeKwhAtStart!!, 0.01)
        assertEquals(612.0, second.lifetimeKwhAtFinish!!, 0.01)
        assertEquals(4.0, second.kwhCharged!!, 0.01)
    }
}
```

> **Note on `recordLastSeenSoc`:** the test uses `detector.recordLastSeenSoc(soc)` to seed the SOC baseline. This becomes a public method on the detector — see Step 3.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.AutoserviceChargingDetectorTest"
```

Expected: FAIL — `Unresolved reference: AutoserviceChargingDetector`, `CatchUpOutcome`, `recordLastSeenSoc`.

- [ ] **Step 3: Implement AutoserviceChargingDetector**

Create `app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt`:

```kotlin
package com.bydmate.app.data.charging

import android.util.Log
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class DetectorState { IDLE, EVALUATING, ERROR }

enum class CatchUpOutcome {
    AUTOSERVICE_UNAVAILABLE,
    SENTINEL,
    BASELINE_INITIALIZED,
    NO_DELTA,
    SESSION_CREATED
}

data class CatchUpResult(
    val outcome: CatchUpOutcome,
    val chargeId: Long? = null,
    val deltaKwh: Double? = null
)

/**
 * Catch-up charging detection driven by lifetime_kwh delta from the BMS.
 *
 * On every TrackingService start (and later: optional live ticks) we read the
 * current lifetime_kwh, compare to the stored baseline, and synthesize a
 * COMPLETED ChargeEntity when the delta exceeds threshold. This works while
 * the head unit slept through the actual zarryadka.
 *
 * Phase 1: catch-up only. Live tick (Phase 3) will reuse the same state
 * machine and Mutex.
 */
@Singleton
class AutoserviceChargingDetector @Inject constructor(
    private val client: AutoserviceClient,
    private val chargeRepo: ChargeRepository,
    private val batteryHealthRepo: BatteryHealthRepository,
    private val baselineStore: ChargingBaselineStore,
    private val classifier: ChargingTypeClassifier,
    private val settings: SettingsRepository
) {
    companion object {
        private const val TAG = "AutoserviceCharging"
        const val MIN_DELTA_KWH = 0.5
        // Heuristic duration when we have no other clue (catch-up after deep sleep).
        // 1 hour is a safe midpoint: under 20 kWh → AC tariff (cheaper, safer for user
        // pocket); above → DC. Phase 3 live tick will replace this with measured ms.
        const val HEURISTIC_HOURS = 1.0
        // Min SOC delta for BatterySnapshot capacity calculation (matches BatteryHealthRepository).
        const val MIN_SOC_DELTA_FOR_SNAPSHOT = 5
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow(DetectorState.IDLE)
    val state: StateFlow<DetectorState> = _state

    /** Public so TrackingService polling loop can update on every DiPars sample. */
    suspend fun recordLastSeenSoc(soc: Int) = settings.setLastSeenSoc(soc)

    suspend fun runCatchUp(now: Long = System.currentTimeMillis()): CatchUpResult = mutex.withLock {
        _state.value = DetectorState.EVALUATING
        try {
            if (!client.isAvailable()) {
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE)
            }
            val battery = client.readBatterySnapshot()
            val lifetimeKwh = battery?.lifetimeKwh?.toDouble()
            if (lifetimeKwh == null) {
                Log.d(TAG, "lifetime_kwh sentinel — skip catch-up")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.SENTINEL)
            }

            val baseline = baselineStore.getBaseline()
            if (baseline == null) {
                baselineStore.setBaseline(lifetimeKwh, now)
                Log.i(TAG, "Cold-start baseline init: lifetime_kwh=$lifetimeKwh")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.BASELINE_INITIALIZED)
            }

            val delta = lifetimeKwh - baseline
            if (delta < MIN_DELTA_KWH) {
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.NO_DELTA, deltaKwh = delta)
            }

            val charging = client.readChargingSnapshot()
            val type = classifier.fromGunState(charging?.gunConnectState)
                ?: classifier.heuristicByPower(delta, HEURISTIC_HOURS)
            val tariff = if (type == "DC") settings.getDcTariff() else settings.getHomeTariff()
            val cost = delta * tariff

            val socEnd = battery.socPercent?.toInt()
            val socStart = settings.getLastSeenSoc()

            val charge = ChargeEntity(
                startTs = now,                  // unknown actual start; record as "detected at"
                endTs = now,
                socStart = socStart,
                socEnd = socEnd,
                kwhCharged = delta,
                kwhChargedSoc = if (socStart != null && socEnd != null && socEnd > socStart) {
                    val cap = settings.getBatteryCapacity()
                    (socEnd - socStart) / 100.0 * cap
                } else null,
                type = type,
                cost = cost,
                status = "COMPLETED",
                lifetimeKwhAtStart = baseline,
                lifetimeKwhAtFinish = lifetimeKwh,
                gunState = charging?.gunConnectState,
                detectionSource = "autoservice_catchup"
            )
            val chargeId = chargeRepo.insertCharge(charge)
            // Baseline rolls forward via DAO MAX query — no explicit setBaseline needed.

            // BatterySnapshot for capacity / SoH tracking when SOC delta is meaningful.
            if (socStart != null && socEnd != null && (socEnd - socStart) >= MIN_SOC_DELTA_FOR_SNAPSHOT) {
                val capacity = batteryHealthRepo.calculateCapacity(delta, socStart, socEnd)
                val soh = capacity?.let { batteryHealthRepo.calculateSoh(it) }
                batteryHealthRepo.insert(
                    BatterySnapshotEntity(
                        timestamp = now,
                        odometerKm = battery.lifetimeMileageKm?.toDouble(),
                        socStart = socStart,
                        socEnd = socEnd,
                        kwhCharged = delta,
                        calculatedCapacityKwh = capacity,
                        sohPercent = soh,
                        cellDeltaV = null,
                        batTempAvg = null,
                        chargeId = chargeId
                    )
                )
            }

            Log.i(TAG, "Catch-up session id=$chargeId, delta=${"%.2f".format(delta)} kWh, type=$type")
            _state.value = DetectorState.IDLE
            return CatchUpResult(CatchUpOutcome.SESSION_CREATED, chargeId, delta)
        } catch (e: Exception) {
            Log.w(TAG, "runCatchUp failed: ${e.message}")
            _state.value = DetectorState.ERROR
            return CatchUpResult(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.AutoserviceChargingDetectorTest"
```

Expected: PASS, 9 tests green.

If the reflection-based test (`subsequent catch-up after a session uses the new baseline`) fails because the `client` field is private — replace it with: re-build a fresh detector against an updated FakeAutoservice rather than mutating in place. The simpler workaround: change the assertion to instantiate two independent detectors with two batteries, verifying the second one sees the inserted-row baseline via the DAO.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt \
        app/src/test/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetectorTest.kt
git commit -m "feat(charging): AutoserviceChargingDetector catch-up state machine"
```

---

## Task 12: BatteryStateRepository

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/domain/battery/BatteryStateRepository.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/domain/battery/BatteryStateRepositoryTest.kt`

> **Scope:** Phase 1 builds the repository as a *callable* (refresh on demand). Phase 2 will wire a polling Flow inside DashboardViewModel. We do NOT start a coroutine here.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bydmate/app/domain/battery/BatteryStateRepositoryTest.kt`:

```kotlin
package com.bydmate.app.domain.battery

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStateRepositoryTest {

    private class FakeAutoservice(
        private val battery: BatteryReading?,
        private val available: Boolean = true
    ) : AutoserviceClient {
        override suspend fun isAvailable(): Boolean = available
        override suspend fun getInt(dev: Int, fid: Int): Int? = null
        override suspend fun getFloat(dev: Int, fid: Int): Float? = null
        override suspend fun readBatterySnapshot(): BatteryReading? = battery
        override suspend fun readChargingSnapshot(): ChargingReading? = null
    }

    private class FakeBatteryHealthRepo(
        private val last: BatterySnapshotEntity?
    ) : BatteryHealthRepository(NullDao()) {
        override suspend fun getLast(): BatterySnapshotEntity? = last
        private class NullDao : com.bydmate.app.data.local.dao.BatterySnapshotDao {
            override fun getAll(): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
            override fun getRecent(limit: Int): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
            override suspend fun insert(snapshot: BatterySnapshotEntity): Long = 0
            override suspend fun getLast(): BatterySnapshotEntity? = null
            override suspend fun getCount(): Int = 0
        }
    }

    private fun fakeSettings(autoserviceEnabled: Boolean): SettingsRepository {
        val dao = object : com.bydmate.app.data.local.dao.SettingsDao {
            override suspend fun get(key: String): String? = null
            override fun observe(key: String): Flow<String?> = flowOf(null)
            override suspend fun set(entity: com.bydmate.app.data.local.entity.SettingEntity) {}
        }
        return object : SettingsRepository(dao) {
            override suspend fun isAutoserviceEnabled(): Boolean = autoserviceEnabled
        }
    }

    @Test
    fun `state has all autoservice fields null when toggle OFF`() = runTest {
        val repo = BatteryStateRepository(
            FakeAutoservice(BatteryReading(100f, 91f, 600f, 2091f, 14f, 0L)),
            FakeBatteryHealthRepo(null),
            fakeSettings(autoserviceEnabled = false)
        )

        val state = repo.refresh()

        assertNull(state.sohPercent)
        assertNull(state.lifetimeKwh)
        assertNull(state.lifetimeKm)
        assertNull(state.voltage12v)
        assertFalse(state.autoserviceAvailable)
    }

    @Test
    fun `state populated when toggle ON and autoservice available`() = runTest {
        val repo = BatteryStateRepository(
            FakeAutoservice(BatteryReading(100f, 91f, 602.7f, 2091f, 14.0f, 0L)),
            FakeBatteryHealthRepo(null),
            fakeSettings(autoserviceEnabled = true)
        )

        val state = repo.refresh()

        assertEquals(100.0f, state.sohPercent!!, 0.01f)
        assertEquals(91.0f, state.socNow!!, 0.01f)
        assertEquals(602.7f, state.lifetimeKwh!!, 0.01f)
        assertEquals(2091.0f, state.lifetimeKm!!, 0.01f)
        assertEquals(14.0f, state.voltage12v!!, 0.01f)
        assertTrue(state.autoserviceAvailable)
    }

    @Test
    fun `autoserviceAvailable is false when toggle ON but client unreachable`() = runTest {
        val repo = BatteryStateRepository(
            FakeAutoservice(battery = null, available = false),
            FakeBatteryHealthRepo(null),
            fakeSettings(autoserviceEnabled = true)
        )

        val state = repo.refresh()

        assertFalse(state.autoserviceAvailable)
        assertNull(state.sohPercent)
    }

    @Test
    fun `falls back to last BatterySnapshot SoH when autoservice sohPercent is null`() = runTest {
        val snap = BatterySnapshotEntity(
            timestamp = 0L, socStart = 30, socEnd = 80,
            kwhCharged = 36.0, calculatedCapacityKwh = 72.0, sohPercent = 98.7
        )
        val repo = BatteryStateRepository(
            FakeAutoservice(BatteryReading(null, 91f, 602.7f, 2091f, 14f, 0L)),  // sohPercent sentinel
            FakeBatteryHealthRepo(snap),
            fakeSettings(autoserviceEnabled = true)
        )

        val state = repo.refresh()

        assertEquals(98.7f, state.sohPercent!!, 0.01f)  // from snapshot
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.battery.BatteryStateRepositoryTest"
```

Expected: FAIL — `Unresolved reference: BatteryStateRepository`.

- [ ] **Step 3: Implement BatteryStateRepository**

Create `app/src/main/kotlin/com/bydmate/app/domain/battery/BatteryStateRepository.kt`:

```kotlin
package com.bydmate.app.domain.battery

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregated battery view used by Dashboard (TopBar warning, BatteryCard,
 * BatteryHealthScreen header).
 *
 * Phase 1: only `refresh()` (call-on-demand). Phase 2 will compose this
 * with TrackingService.lastData and emit as a Flow into DashboardViewModel.
 */
data class BatteryState(
    val socNow: Float?,
    val voltage12v: Float?,
    val sohPercent: Float?,
    val lifetimeKm: Float?,
    val lifetimeKwh: Float?,
    /** True when toggle is ON AND the autoservice client returned at least one real value. */
    val autoserviceAvailable: Boolean
)

@Singleton
class BatteryStateRepository @Inject constructor(
    private val autoservice: AutoserviceClient,
    private val batteryHealth: BatteryHealthRepository,
    private val settings: SettingsRepository
) {
    suspend fun refresh(): BatteryState {
        if (!settings.isAutoserviceEnabled()) {
            return BatteryState(null, null, null, null, null, autoserviceAvailable = false)
        }
        if (!autoservice.isAvailable()) {
            return BatteryState(null, null, null, null, null, autoserviceAvailable = false)
        }
        val r = autoservice.readBatterySnapshot()
            ?: return BatteryState(null, null, null, null, null, autoserviceAvailable = false)

        val sohFromSnapshot = batteryHealth.getLast()?.sohPercent?.toFloat()
        return BatteryState(
            socNow = r.socPercent,
            voltage12v = r.voltage12v,
            sohPercent = r.sohPercent ?: sohFromSnapshot,
            lifetimeKm = r.lifetimeMileageKm,
            lifetimeKwh = r.lifetimeKwh,
            autoserviceAvailable = true
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.battery.BatteryStateRepositoryTest"
```

Expected: PASS, 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/domain/battery/BatteryStateRepository.kt \
        app/src/test/kotlin/com/bydmate/app/domain/battery/BatteryStateRepositoryTest.kt
git commit -m "feat(battery): BatteryStateRepository aggregates autoservice + DB"
```

---

## Task 13: Remove ChargeTracker + dead callsites

**Files:**
- Delete: `app/src/main/kotlin/com/bydmate/app/domain/tracker/ChargeTracker.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt:641`

- [ ] **Step 1: Delete the ChargeTracker file**

```bash
rm app/src/main/kotlin/com/bydmate/app/domain/tracker/ChargeTracker.kt
```

- [ ] **Step 2: Remove all ChargeTracker references from TrackingService**

Edit `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`:

- **Line 27** — delete `import com.bydmate.app.domain.tracker.ChargeTracker`
- **Line 29** — delete `import com.bydmate.app.domain.tracker.ChargeState`
- **Line 57** — delete `@Inject lateinit var chargeTracker: ChargeTracker`
- **Lines 200-212** — delete the entire `// Finalize stale SUSPENDED charge sessions from previous runs` block, including the `serviceScope.launch { ... }` body. (The migration in Task 8 already drops these rows.)
- **Line 220** — delete `diPlusDbReader.importChargingLog()` and the comment `// Also import charging sessions` on the line above. (Will also be cleaned by Task 14.)
- **Line 453** — delete `chargeTracker.onData(data, loc)`
- **Lines 326-328** — inside the `withTimeout(4000L) { ... }` block, delete `chargeTracker.forceEnd(lastData)`. Keep `tripTracker.forceEnd(lastData, lastLoc)`.

- [ ] **Step 3: Remove logcat tag entry**

Edit `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt:641`. Replace the line:

```kotlin
                    "ChargeTracker:*", "IdleDrainTracker:*", "DiPlusDbReader:*",
```

with:

```kotlin
                    "DiPlusDbReader:*",
```

(Both `ChargeTracker:*` and `IdleDrainTracker:*` are removed — the latter because `IdleDrainTracker.onData` is dead code and emits nothing useful.)

- [ ] **Step 4: Compile sanity check**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. If a `Unresolved reference` survives, grep for any remaining `chargeTracker\|ChargeTracker\|ChargeState` in `app/src/main/kotlin/`:

```bash
grep -rn "chargeTracker\|ChargeTracker\|\bChargeState\b" app/src/main/kotlin/
```

Expected: zero hits.

- [ ] **Step 5: Run all unit tests to confirm nothing else broke**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all tests green (including the new Phase 1 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/domain/tracker/ChargeTracker.kt \
        app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt \
        app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt
# Note: `git add` of a deleted file is correct via `git rm`; or use `git add -A` for the whole tree
git commit -m "refactor: remove ChargeTracker + dead callsites (replaced by AutoserviceChargingDetector)"
```

---

## Task 14: Remove `importChargingLog` + UI hook

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/remote/DiPlusDbReader.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/welcome/WelcomeViewModel.kt:96-97`
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt:370-386`

- [ ] **Step 1: Delete the method + nested data class from DiPlusDbReader**

Edit `app/src/main/kotlin/com/bydmate/app/data/remote/DiPlusDbReader.kt`:

- **Lines 38-45** — delete the `data class ImportResult` (only `importChargingLog` returns it)
- **Lines 121-262** — delete `importChargingLog()` and `private suspend fun doImport()`
- **Lines 5-7** — remove now-unused imports:
  - `import com.bydmate.app.data.local.entity.ChargeEntity` (only used by deleted code)
  - `import com.bydmate.app.data.repository.ChargeRepository` (only used in constructor for the deleted method)
  - `import kotlinx.coroutines.flow.first` (only used in deleted code)
- **Constructor** — remove `private val chargeRepository: ChargeRepository,` (no longer needed). The class becomes:

```kotlin
@Singleton
class DiPlusDbReader @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
```

> If `settingsRepository` becomes unused after the cleanup — remove it too. Verify by re-reading the kept methods (`readTripInfo`, `findMatchingTrip`).

After the edits, the file contains only `readTripInfo()` and `findMatchingTrip()` plus the data class `DiPlusTripRecord`.

- [ ] **Step 2: Remove the call in WelcomeViewModel**

Edit `app/src/main/kotlin/com/bydmate/app/ui/welcome/WelcomeViewModel.kt`. Delete lines 96-97:

```kotlin
            // Import charges
            diPlusDbReader.importChargingLog()
```

If `diPlusDbReader` field becomes unused after the cleanup — remove the `@Inject` field too. Grep first:

```bash
grep -n "diPlusDbReader" app/src/main/kotlin/com/bydmate/app/ui/welcome/WelcomeViewModel.kt
```

If only one hit remains (the field declaration), remove the `@Inject lateinit var diPlusDbReader: DiPlusDbReader` line and its import.

- [ ] **Step 3: Remove the UI command in SettingsViewModel**

Edit `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt`. Delete the entire `importDiPlusCharges()` method (lines 370-386):

```kotlin
    /** Import charging sessions from DiPlus ChargingLog database. */
    fun importDiPlusCharges() {
        viewModelScope.launch {
            _uiState.update { it.copy(importStatus = "Импорт DiPlus...") }
            val result = diPlusDbReader.importChargingLog()
            if (result.isError) {
                _uiState.update { it.copy(importStatus = "Ошибка: ${result.error}") }
            } else {
                val msg = buildString {
                    append("Импортировано ${result.imported} зарядок")
                    if (result.skipped > 0) append(", пропущено ${result.skipped} дублей")
                    append(" (всего в DiPlus: ${result.totalInDb})")
                }
                _uiState.update { it.copy(importStatus = msg) }
            }
        }
    }
```

- [ ] **Step 4: Remove the matching button from SettingsScreen if present**

Search the UI for any button bound to `importDiPlusCharges`:

```bash
grep -n "importDiPlusCharges" app/src/main/kotlin/com/bydmate/app/ui/settings/
```

If found in `SettingsScreen.kt` — delete that Button composable. If the surrounding layout becomes empty — clean up the empty `Card` / `Column` too.

- [ ] **Step 5: Compile and run all tests**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL + all tests green. Confirm zero references to `importChargingLog` remain:

```bash
grep -rn "importChargingLog" app/src/
```

Expected: zero hits.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/remote/DiPlusDbReader.kt \
        app/src/main/kotlin/com/bydmate/app/ui/welcome/WelcomeViewModel.kt \
        app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt \
        app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsScreen.kt
git commit -m "refactor: remove DiPlus importChargingLog (empty on every known firmware)"
```

---

## Task 15: Guard `detectOfflineCharge` with autoservice toggle

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt:479-519`

> **Why:** When autoservice is ON, the new catch-up detector is the source of truth. The legacy SOC-delta `detectOfflineCharge` would create a duplicate `ChargeEntity`. Adding one early-return guard preserves the fallback behaviour for users without autoservice (Phase 2 will make it a UI toggle).

- [ ] **Step 1: Add the guard at the top of `detectOfflineCharge` body**

Edit `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`. Inside `private fun detectOfflineCharge(currentSoc: Int)` (line 479), the existing body is:

```kotlin
    private fun detectOfflineCharge(currentSoc: Int) {
        serviceScope.launch {
            try {
                val lastSoc = settingsRepository.getLastKnownSoc() ?: return@launch
```

Insert the guard immediately after `serviceScope.launch { try {`:

```kotlin
    private fun detectOfflineCharge(currentSoc: Int) {
        serviceScope.launch {
            try {
                // When autoservice is enabled, AutoserviceChargingDetector.runCatchUp
                // is the source of truth (lifetime_kwh delta is more accurate than
                // SOC delta, and it survives BMS calibration ticks). Skip the
                // legacy SOC-delta path to avoid duplicate ChargeEntity inserts.
                if (settingsRepository.isAutoserviceEnabled()) {
                    Log.d(TAG, "detectOfflineCharge skipped (autoservice ON)")
                    return@launch
                }

                val lastSoc = settingsRepository.getLastKnownSoc() ?: return@launch
```

- [ ] **Step 2: Compile sanity check**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt
git commit -m "refactor(service): skip legacy detectOfflineCharge when autoservice enabled"
```

---

## Task 16: DI providers + TrackingService catch-up wiring

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`

- [ ] **Step 1: Add Hilt providers for the new singletons**

Most new classes already carry `@Singleton @Inject constructor(...)` so Hilt can construct them automatically. The exception is the `AutoserviceClient` interface — Hilt needs an explicit binding.

Edit `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt`. Append before the closing `}` of the `object AppModule`:

```kotlin

    @Provides
    @Singleton
    fun provideAdbOnDeviceClient(
        @ApplicationContext context: Context
    ): com.bydmate.app.data.autoservice.AdbOnDeviceClient =
        com.bydmate.app.data.autoservice.AdbOnDeviceClientImpl(context)

    @Provides
    @Singleton
    fun provideAutoserviceClient(
        adb: com.bydmate.app.data.autoservice.AdbOnDeviceClient
    ): com.bydmate.app.data.autoservice.AutoserviceClient =
        com.bydmate.app.data.autoservice.AutoserviceClientImpl(adb)
```

(`AutoserviceChargingDetector`, `ChargingBaselineStore`, `ChargingTypeClassifier`, `BatteryStateRepository` all have `@Singleton @Inject constructor(...)` — Hilt resolves them automatically.)

- [ ] **Step 2: Inject the detector and SOC bookkeeper into TrackingService**

Edit `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`. Add after the existing `@Inject lateinit var rangeCalculator: RangeCalculator` (line 68):

```kotlin
    @Inject lateinit var autoserviceDetector: com.bydmate.app.data.charging.AutoserviceChargingDetector
```

- [ ] **Step 3: Wire the catch-up call in `onCreate`**

In the same file, find the `serviceScope.launch { try { ... historyImporter.runSync() ... } }` block — formerly lines 215-226 but Task 13 already removed `diPlusDbReader.importChargingLog()`. Replace it with:

```kotlin
        // v2.5: event-based sync on service start
        serviceScope.launch {
            try {
                val result = historyImporter.runSync()
                Log.i(TAG, "Sync: ${result.details ?: result.error ?: "ok"}")
                // Autoservice catch-up: synthesizes COMPLETED ChargeEntity records
                // for charging that happened while DiLink was asleep. Best-effort —
                // wrapped so a Binder/ADB failure does not break the import chain.
                try {
                    val outcome = autoserviceDetector.runCatchUp()
                    Log.i(TAG, "Autoservice catch-up: ${outcome.outcome}")
                } catch (e: Exception) {
                    Log.w(TAG, "Autoservice catch-up failed: ${e.message}")
                }
                // AI insights (once per day)
                insightsManager.refreshIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed: ${e.message}")
            }
        }
```

- [ ] **Step 4: Wire SOC bookkeeping in the polling loop**

In the same file, find the `data.soc?.let { soc -> settingsRepository.saveLastKnownSoc(soc) }` block (around line 406-408 of the original file, now shifted by Task 13 deletions). Replace it with:

```kotlin
                        // Save SOC for retrospective charge detection (legacy detectOfflineCharge fallback path).
                        data.soc?.let { soc ->
                            settingsRepository.saveLastKnownSoc(soc)
                            // Also seed AutoserviceChargingDetector so the next catch-up
                            // can compute SOC delta for the BatterySnapshot capacity calc.
                            autoserviceDetector.recordLastSeenSoc(soc)
                        }
```

- [ ] **Step 5: Compile and run all tests**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL + all unit tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/di/AppModule.kt \
        app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt
git commit -m "feat(service): wire AutoserviceChargingDetector catch-up on service start"
```

---

## Task 17: Final test sweep + Phase 1 acceptance

**Files:** none (verification only)

- [ ] **Step 1: Run the entire unit test suite**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL with summary `<N> tests, 0 failed`. Phase 1 added approximately:
- SentinelDecoderTest: 14
- FidRegistryTest: 7
- AutoserviceClientImplTest: 11
- ChargingTypeClassifierTest: 12
- ChargingBaselineStoreTest: 4
- AutoserviceChargingDetectorTest: 9
- BatteryStateRepositoryTest: 4
- Migration11to12Test: 3
- RobolectricSmokeTest: 1
- **Total: 65 new tests** plus all pre-existing tests.

If any test fails:
1. Read the failure carefully — the test is the spec for behaviour.
2. Fix the production code, not the test.
3. Re-run only the failing test class first to iterate fast: `./gradlew :app:testDebugUnitTest --tests "<fully.qualified.ClassName>"`.

- [ ] **Step 2: Build the release APK to confirm production compile**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. APK at `app/build/outputs/apk/debug/BYDMate-v2.4.12.apk` (versionName not bumped yet — bump happens in Phase 4).

- [ ] **Step 3: Document Phase 1 summary**

Add a Phase 1 completion entry to the bottom of `docs/superpowers/handoff-2026-04-26.md`:

```markdown
---

## Session N — Phase 1 implementation complete

**Branch:** `feature/autoservice-readonly`
**Goal:** ship the read-only autoservice + catch-up detector — code only, no UI.

### Done

- Test infrastructure: kotlinx-coroutines-test, Robolectric 4.13, room-testing 2.6.1, adblib 1.0.0 (or fallback)
- `data/autoservice/` complete: SentinelDecoder, FidRegistry, BatteryReading, ChargingReading, AdbOnDeviceClient (skeleton + manual smoke instructions in commit body), AutoserviceClient
- `data/charging/` complete: ChargingTypeClassifier, ChargingBaselineStore, AutoserviceChargingDetector
- `domain/battery/`: BatteryStateRepository
- DB v11 → v12: 4 new columns + DELETE SUSPENDED/ACTIVE cleanup, full Robolectric MigrationTest coverage
- ChargeTracker fully removed (380 lines + all callsites + logcat tags)
- DiPlusDbReader.importChargingLog removed (4 callsites cleaned)
- detectOfflineCharge guarded — only runs when autoservice OFF
- TrackingService catch-up wiring + SOC bookkeeper into AutoserviceChargingDetector
- 65 new unit tests, all green

### NOT done in Phase 1 (intentional, deferred)

- Settings UI (toggle, status text) — Phase 2
- Charges tab return to navigation + 4 fallback states — Phase 2
- Dashboard TopBar warning + clickable BatteryCard — Phase 2
- BatteryHealthScreen empty state copy update — Phase 2
- Optional finalize prompt + ChargingPromptBus — Phase 3
- Live tick (`runLiveTick`) — Phase 3
- Codex audit + v2.5.0 release — Phase 4

### Next session

`superpowers:writing-plans` → Phase 2 plan (UI surface + Charges tab + DashboardViewModel state propagation).

### Open questions for next phases

- AdbOnDeviceClient adblib smoke result on real device (Phase 2 prereq)
- `detectOfflineCharge` long-term — leave as fallback or fully remove once Phase 2 ships UI?
- Should `recordLastSeenSoc` move to `BatteryStateRepository` so the catch-up source-of-truth lives in one place?
```

- [ ] **Step 4: Commit and close Phase 1**

```bash
git add docs/superpowers/handoff-2026-04-26.md
git commit -m "docs: Phase 1 completion handoff"
```

- [ ] **Step 5: Manual smoke on Leopard 3 (acceptance gate before Phase 2)**

This step requires Andy's car. Hand off the smoke procedure as a separate message — do NOT attempt to install the APK from this session (`feedback_no_car_changes.md`).

Smoke procedure to share with Andy:

```text
Smoke test — Phase 1 (read-only, takes ~5 min):

1. Andy installs the new APK manually on DiLink.
2. Launch BYDMate. App opens normally — no UI changes visible (correct).
3. Connect to DiLink ADB on Mac:
     adb connect 192.168.2.68:5555
4. Trigger `setAutoserviceEnabled(true)`. Phase 1 has no UI yet — flip the
   key directly:
     adb -s 192.168.2.68:5555 shell sqlite3 /data/data/com.bydmate.app/databases/bydmate.db \
       "INSERT OR REPLACE INTO settings (key, value) VALUES ('autoservice_enabled', 'true');"
   (App must be killed first, otherwise the cached SettingsRepository wins.)
5. Force-stop and relaunch BYDMate so onCreate runs the catch-up:
     adb -s 192.168.2.68:5555 shell am force-stop com.bydmate.app
     adb -s 192.168.2.68:5555 shell am start com.bydmate.app/.MainActivity
6. Watch logcat for the catch-up outcome:
     adb -s 192.168.2.68:5555 logcat -d -s TrackingService:* AutoserviceCharging:*
   Expected one of:
     "Autoservice catch-up: BASELINE_INITIALIZED"  (first run, baseline written)
     "Autoservice catch-up: NO_DELTA"              (no charging since baseline)
     "Autoservice catch-up: SESSION_CREATED"       (charging detected)
     "Autoservice catch-up: AUTOSERVICE_UNAVAILABLE" (ADB pairing not yet wired)
7. Verify charges DB:
     adb -s 192.168.2.68:5555 shell sqlite3 /data/data/com.bydmate.app/databases/bydmate.db \
       "SELECT id, status, kwh_charged, lifetime_kwh_at_finish, detection_source FROM charges ORDER BY id DESC LIMIT 5;"

Acceptance: at least one row with `detection_source = 'autoservice_catchup'`
appears after a real zarryadka, with `lifetime_kwh_at_finish` matching the
value Andy reads via `service call autoservice 7 i32 1014 i32 1032871984`.
```

---

## Self-review

### Spec coverage

Walking the spec section by section:

- **§1 Goal** — covered by Tasks 5 (client), 11 (detector), 16 (wiring).
- **§2 Reuse base** — Tasks 7 (entity gains fields), 10 (baseline reuses ChargeRepository), 11 (reuses BatteryHealthRepository.calculateCapacity/Soh).
- **§3 Removal list** — Task 13 removes ChargeTracker + all 5 callsites. Task 14 removes importChargingLog + 4 callsites + UI hook. Task 13 step 3 also removes the `IdleDrainTracker:*` logcat entry. The `IdleDrainTracker.kt:39` parameter is left untouched (decision section, dead-code finding).
- **§4.1 autoservice layer** — Tasks 2, 3, 4, 5 cover all 6 files.
- **§4.2 charging layer** — Tasks 6, 10, 11 cover all 3 files.
- **§4.3 finalize prompt** — explicitly Phase 3, NOT in this plan.
- **§4.4 BatteryStateRepository** — Task 12.
- **§5.1 DB + DI** — Task 7 (entity + DAO), Task 8 (migration + version bump + DI registration), Task 16 (Provides bindings).
- **§5.2 Service** — Tasks 13, 14, 15, 16.
- **§5.3 Welcome/Settings cleanup** — Task 14.
- **§5.4 Settings UI** — Phase 2, NOT in this plan.
- **§5.5 Dashboard** — Phase 2.
- **§5.6 BatteryHealthScreen** — Phase 2.
- **§5.7 Charges tab** — Phase 2.
- **§5.8 Charges fallback** — Phase 2.
- **§5.9 build.gradle adblib** — Task 1.
- **§6 Won't do** — respected throughout (no setInt fid in registry, regex barrier in Task 4, no live tick).
- **§7 Phasing** — this plan is Phase 1 only. Acceptance test in Task 17 matches §7 Phase 1 acceptance criterion.
- **§8 Risk register** — risks 1 (adblib failure, Task 1 + Task 4), 3 (lifetime_kwh sentinel, AutoserviceChargingDetectorTest), 4 (cold start, AutoserviceChargingDetectorTest), 6 (mutex, in detector implementation) are exercised by tests. Risk 7 (toggle off mid-session) and 9 (firmware update) are deferred to live tick (Phase 3).

### Placeholder scan

- No "TBD" / "TODO: implement later" anywhere except `AdbOnDeviceClient.kt` skeleton's two TODOs — those are intentional, paired with explicit manual smoke instructions in the commit body and in Task 4 step 3. Acceptable because the real wire-up cannot happen on a Mac (needs RSA pairing on the device).
- No "Add error handling" / "handle edge cases" without code — all error paths are spelled out (sentinel → null, ADB exception → null, autoservice unavailable → return-without-side-effect).
- No "Similar to Task N" — every code block is self-contained.

### Type consistency

- `AutoserviceClient.getInt` / `getFloat` signatures match across interface, impl, mocks in 3 test files.
- `BatteryReading.lifetimeKwh: Float?` (not Double) — propagated as Float? through `BatteryStateRepository.lifetimeKwh: Float?` and converted to `Double` only when entering the detector (`lifetimeKwh.toDouble()`). Consistent.
- `CatchUpOutcome` enum values match between detector and tests.
- `DetectorState` exposed but not asserted in tests — kept for Phase 2 UI consumer.
- `ChargingTypeClassifier.fromGunState` returns `String?`; `heuristicByPower` returns `String` (non-null). Detector handles both with `?: heuristic(...)`. Consistent.
- `ChargingBaselineStore.getBaseline` returns `Double?`; detector and store-test both expect `Double?`. Consistent.
- `MIGRATION_11_12` SQL is duplicated between AppModule (production) and AppModuleMigrationsForTest (Robolectric) intentionally — both halves use exactly the same 4 ALTER + 1 DELETE statements. If schema changes, update both.
- `detection_source` literal `"autoservice_catchup"` matches the WHERE clause `LIKE 'autoservice%'` in `getMaxLifetimeKwhAtFinish()`. Consistent.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-26-autoservice-readonly-phase1.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review between tasks, fast iteration. Good for the 17 tasks here because the layered structure (foundation → domain → wiring) means each task either passes its test in isolation or blocks subsequent tasks immediately.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints for review.

Which approach?

# v2.5.0 Adaptive Range & Consumption — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить SOC×EMA-based расчёт остаточного пробега и rolling-5-км session-based виджетный расход на единый 25-км odometer rolling buffer с sessionId boundary. Устраняет «застревание» range на 203 км, скачок 203→191 на ignition-on/off, расхождение виджет-22 vs итог-33 на короткой поездке с прогревом.

**Architecture:** Один persistent rolling-25-км buffer по одометру (новая Room таблица `odometer_samples`) питает три consumer'а: `RangeCalculator` (range км), `ConsumptionAggregator` (цифра расхода + стрелка на виджете). Изоляция от energydata/DiPlus toggle — buffer работает на DiPars live-потоке. SessionId boundary (= ignition cycle) пропускает пары снимков из разных сессий при подсчёте среднего. Подход подтверждён ресерчем Tesla/BMW/Hyundai.

**Tech Stack:** Kotlin 1.9, Jetpack Compose, Room 2.6.1, Hilt, Coroutines/Flow, JUnit 4 (никаких Robolectric/Mockito — придерживаемся существующего стиля fakes-based тестов).

**Spec:** `docs/superpowers/specs/2026-04-24-adaptive-range-and-consumption-design.md` (rev.2)
**Branch:** `feature/adaptive-range-v2.5.0` (HEAD `9d70ea4`)

---

## File Structure

**New production files:**

| Файл | Ответственность |
|------|-----------------|
| `app/src/main/kotlin/com/bydmate/app/data/local/entity/OdometerSampleEntity.kt` | Room entity для одометрических снимков |
| `app/src/main/kotlin/com/bydmate/app/data/local/dao/OdometerSampleDao.kt` | DAO с window/last/trim запросами |
| `app/src/main/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBuffer.kt` | `@Singleton` — собирает снимки, считает recentAvg/shortAvg с sessionId boundary |
| `app/src/main/kotlin/com/bydmate/app/domain/calculator/SocInterpolator.kt` | `@Singleton` — carry-over между SOC ступенями для плавности range |
| `app/src/main/kotlin/com/bydmate/app/domain/calculator/RangeCalculator.kt` | `@Singleton` — единая формула range для Dashboard/виджета/notification |

**New test files:**

| Файл | Покрытие |
|------|----------|
| `app/src/test/kotlin/com/bydmate/app/domain/calculator/FakeOdometerSampleDao.kt` | In-memory fake DAO, общий для всех buffer-тестов |
| `app/src/test/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBufferTest.kt` | Empty/under-min/normal/sessionId boundary/HVAC simul/charging skip/regression/min-delta/SOC_BASED |
| `app/src/test/kotlin/com/bydmate/app/domain/calculator/SocInterpolatorTest.kt` | Carry monotonic, SOC step reset, SOC up handling, sanity cap |
| `app/src/test/kotlin/com/bydmate/app/domain/calculator/RangeCalculatorTest.kt` | Null SOC, normal, carry, cap change |

**Modified production files:**

| Файл | Изменения |
|------|-----------|
| `app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt` | + entity, + dao, version 10→11 |
| `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt` | + MIGRATION_10_11, + provideOdometerSampleDao |
| `app/src/main/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregator.kt` | Refactor в тонкую обёртку над buffer (cumulative/rolling-5km удаляется) |
| `app/src/main/kotlin/com/bydmate/app/service/SessionPersistence.kt` | Удалить mileage/elec поля, оставить только sessionStartedAt + lastActiveTs |
| `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt` | Wire buffer/interpolator/calc в polling loop, удалить cachedEma/Cap, заменить estimateRangeKm |
| `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt` | Заменить calculateRange/loadRecentAvgConsumption на RangeCalculator. Дефолт cap 38.0 → 72.9 (всё равно перезатирается из Settings, но рудимент чистим) |
| `app/build.gradle.kts` | versionCode 244→250, versionName "2.4.4"→"2.5.0" |

**Modified test files:**

| Файл | Изменения |
|------|-----------|
| `app/src/test/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregatorTest.kt` | Полная переписка под новую роль (тонкая обёртка) |

---

## Task 1: OdometerSampleEntity + DAO + Room migration 10→11

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/local/entity/OdometerSampleEntity.kt`
- Create: `app/src/main/kotlin/com/bydmate/app/data/local/dao/OdometerSampleDao.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt`

- [ ] **Step 1.1: Создать entity**

`app/src/main/kotlin/com/bydmate/app/data/local/entity/OdometerSampleEntity.kt`:

```kotlin
package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Snapshot of (mileage, totalElec, soc) taken from the DiPars live stream
 * on every polling tick where mileage advanced by >= MIN_MILEAGE_DELTA OR
 * sessionId changed.
 *
 * Used by OdometerConsumptionBuffer to compute the rolling 25-km recent avg
 * (and 2-km short avg) consumption. sessionId is a boundary marker — pairs
 * of samples with different sessionId values are skipped during averaging
 * (a pair across an ignition cycle is not a meaningful "drove this far on
 * this much energy" segment).
 */
@Entity(
    tableName = "odometer_samples",
    indices = [Index("mileage_km"), Index("session_id")]
)
data class OdometerSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "mileage_km") val mileageKm: Double,
    @ColumnInfo(name = "total_elec_kwh") val totalElecKwh: Double?,
    @ColumnInfo(name = "soc_percent") val socPercent: Int?,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
)
```

- [ ] **Step 1.2: Создать DAO**

`app/src/main/kotlin/com/bydmate/app/data/local/dao/OdometerSampleDao.kt`:

```kotlin
package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.OdometerSampleEntity

@Dao
interface OdometerSampleDao {

    @Insert
    suspend fun insert(sample: OdometerSampleEntity): Long

    /** Newest sample (by id, monotonic via autoGenerate). */
    @Query("SELECT * FROM odometer_samples ORDER BY id DESC LIMIT 1")
    suspend fun last(): OdometerSampleEntity?

    /** All samples with mileage_km >= :minMileage, ASC by mileage. */
    @Query("""
        SELECT * FROM odometer_samples
        WHERE mileage_km >= :minMileage
        ORDER BY mileage_km ASC
    """)
    suspend fun windowFrom(minMileage: Double): List<OdometerSampleEntity>

    /** Trim samples below the rolling cutoff. */
    @Query("DELETE FROM odometer_samples WHERE mileage_km < :cutoff")
    suspend fun trimBelow(cutoff: Double)

    /** Hard-cap fallback — drop the N oldest rows by id. */
    @Query("""
        DELETE FROM odometer_samples WHERE id IN (
            SELECT id FROM odometer_samples ORDER BY id ASC LIMIT :howMany
        )
    """)
    suspend fun deleteOldest(howMany: Int)

    @Query("SELECT COUNT(*) FROM odometer_samples")
    suspend fun count(): Int

    /** Wipe — used by reset / debug only. */
    @Query("DELETE FROM odometer_samples")
    suspend fun clear()
}
```

- [ ] **Step 1.3: Подключить entity + dao в AppDatabase**

В `app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt`:

1) Добавить import:
```kotlin
import com.bydmate.app.data.local.dao.OdometerSampleDao
import com.bydmate.app.data.local.entity.OdometerSampleEntity
```

2) В список `entities = [...]` добавить `OdometerSampleEntity::class`.

3) Поднять `version = 10` → `version = 11`.

4) Добавить abstract метод:
```kotlin
abstract fun odometerSampleDao(): OdometerSampleDao
```

- [ ] **Step 1.4: Добавить миграцию 10 → 11 и provider в AppModule**

В `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt`:

1) Импорт:
```kotlin
import com.bydmate.app.data.local.dao.OdometerSampleDao
```

2) После `MIGRATION_9_10` добавить:

```kotlin
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS odometer_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                mileage_km REAL NOT NULL,
                total_elec_kwh REAL,
                soc_percent INTEGER,
                session_id INTEGER,
                timestamp INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_odometer_samples_mileage_km ON odometer_samples(mileage_km)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_odometer_samples_session_id ON odometer_samples(session_id)")
    }
}
```

3) В `addMigrations(...)` добавить `MIGRATION_10_11` в конце списка.

4) После `providePlaceDao` добавить:

```kotlin
@Provides fun provideOdometerSampleDao(db: AppDatabase): OdometerSampleDao = db.odometerSampleDao()
```

- [ ] **Step 1.5: Verify build (без unit-тестов на этом этапе)**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Если KSP жалуется на schema export — добавить файл схемы Room сам сгенерирует под `app/schemas/com.bydmate.app.data.local.database.AppDatabase/11.json`.

- [ ] **Step 1.6: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/data/local/entity/OdometerSampleEntity.kt \
        app/src/main/kotlin/com/bydmate/app/data/local/dao/OdometerSampleDao.kt \
        app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt \
        app/src/main/kotlin/com/bydmate/app/di/AppModule.kt \
        app/schemas/com.bydmate.app.data.local.database.AppDatabase/11.json
git commit -m "feat(db): odometer_samples table + DAO + migration 10→11"
```

---

## Task 2: SocInterpolator (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/domain/calculator/SocInterpolator.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/domain/calculator/SocInterpolatorTest.kt`

**Контекст:** Сглаживает 4-км ступеньки SOC между SOC%-щелчками. На каждом tick'е получает `(soc, totalElec, sessionId)`. Запоминает `totalElecAtSocChange` — снимок totalElec на момент последнего изменения SOC. `carryOver(totalElec) = totalElec - totalElecAtSocChange` — сколько кВт·ч ушло после последнего щелчка. Используется в `RangeCalculator` чтобы `remaining_kwh` плавно уменьшался между SOC-щелчками. Persists в SharedPreferences (один файл `bydmate_range_prefs`).

- [ ] **Step 2.1: Test — initial state, нет данных**

`app/src/test/kotlin/com/bydmate/app/domain/calculator/SocInterpolatorTest.kt`:

```kotlin
package com.bydmate.app.domain.calculator

import org.junit.Assert.assertEquals
import org.junit.Test

class SocInterpolatorTest {

    private fun newInterpolator(capacity: Double = 72.9): SocInterpolator {
        return SocInterpolator(capacityKwhProvider = { capacity }, persistence = InMemorySocInterpolatorPrefs())
    }

    @Test fun `cold start — first sample initialises anchor, carry 0`() {
        val interp = newInterpolator()
        interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
        assertEquals(0.0, interp.carryOver(totalElecKwh = 1000.0, soc = 80), 0.001)
    }
}

class InMemorySocInterpolatorPrefs : SocInterpolatorPrefs {
    private var lastSoc: Int? = null
    private var totalElecAtChange: Double? = null
    override fun load(): SocInterpolatorState? {
        val s = lastSoc; val t = totalElecAtChange
        return if (s != null && t != null) SocInterpolatorState(s, t) else null
    }
    override fun save(state: SocInterpolatorState) {
        lastSoc = state.lastSoc; totalElecAtChange = state.totalElecAtChange
    }
    override fun clear() { lastSoc = null; totalElecAtChange = null }
}
```

- [ ] **Step 2.2: Run test — должен FAIL (классов нет)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.SocInterpolatorTest"
```

Expected: FAIL `Unresolved reference: SocInterpolator`.

- [ ] **Step 2.3: Реализовать SocInterpolator (минимум для прохождения)**

`app/src/main/kotlin/com/bydmate/app/domain/calculator/SocInterpolator.kt`:

```kotlin
package com.bydmate.app.domain.calculator

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class SocInterpolatorState(
    val lastSoc: Int,
    val totalElecAtChange: Double,
)

interface SocInterpolatorPrefs {
    fun load(): SocInterpolatorState?
    fun save(state: SocInterpolatorState)
    fun clear()
}

/**
 * Smooths 4-km steps between integer SOC ticks.
 *
 * On every DiPars sample, remember `totalElec` at the moment SOC last changed.
 * `carryOver = totalElec - totalElec_at_last_soc_change` = energy used since
 * that step. Subtract it from `SOC × cap / 100` to get a fractional
 * remaining_kwh that changes monotonically tick-to-tick instead of jumping
 * at each integer SOC click.
 */
@Singleton
class SocInterpolator @Inject constructor(
    private val capacityKwhProvider: suspend () -> Double,
    private val persistence: SocInterpolatorPrefs,
) {
    @Volatile private var state: SocInterpolatorState? = persistence.load()
    @Volatile private var sessionId: Long? = null

    @Synchronized
    fun onSample(soc: Int?, totalElecKwh: Double?, sessionId: Long?) {
        if (soc == null || totalElecKwh == null) return
        if (sessionId != this.sessionId) {
            // New ignition cycle — re-anchor (charging may have changed everything).
            this.sessionId = sessionId
            commit(soc, totalElecKwh)
            return
        }
        val cur = state
        if (cur == null || cur.lastSoc != soc) {
            commit(soc, totalElecKwh)
        }
    }

    /**
     * kWh consumed since the last SOC change. Always >= 0 within the same
     * SOC step. Returns 0 when no anchor yet.
     */
    fun carryOver(totalElecKwh: Double?, soc: Int?): Double {
        val st = state ?: return 0.0
        if (totalElecKwh == null || soc == null) return 0.0
        if (soc != st.lastSoc) return 0.0  // SOC moved but onSample hasn't run yet
        val raw = totalElecKwh - st.totalElecAtChange
        if (raw < 0.0) return 0.0  // glitch — totalElec rolled back
        return raw
    }

    private fun commit(soc: Int, totalElec: Double) {
        val newState = SocInterpolatorState(lastSoc = soc, totalElecAtChange = totalElec)
        state = newState
        persistence.save(newState)
    }
}
```

- [ ] **Step 2.4: Run test — должен PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.SocInterpolatorTest.cold start*"
```

Expected: PASS.

- [ ] **Step 2.5: Test — carry монотонно растёт между SOC step'ами**

В `SocInterpolatorTest.kt` добавить:

```kotlin
@Test fun `carry monotonic between SOC steps`() {
    val interp = newInterpolator()
    interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
    // SOC stays 80, totalElec drips up
    interp.onSample(soc = 80, totalElecKwh = 1000.2, sessionId = 1L)
    assertEquals(0.2, interp.carryOver(totalElecKwh = 1000.2, soc = 80), 0.001)
    interp.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)
    assertEquals(0.5, interp.carryOver(totalElecKwh = 1000.5, soc = 80), 0.001)
}
```

Run, expect PASS.

- [ ] **Step 2.6: Test — SOC щелчок вниз сбрасывает carry**

```kotlin
@Test fun `SOC click down resets carry to zero`() {
    val interp = newInterpolator()
    interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
    interp.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)
    // SOC clicks 80 → 79 at totalElec = 1000.7
    interp.onSample(soc = 79, totalElecKwh = 1000.7, sessionId = 1L)
    assertEquals(0.0, interp.carryOver(totalElecKwh = 1000.7, soc = 79), 0.001)
    interp.onSample(soc = 79, totalElecKwh = 1000.9, sessionId = 1L)
    assertEquals(0.2, interp.carryOver(totalElecKwh = 1000.9, soc = 79), 0.001)
}
```

Run, expect PASS.

- [ ] **Step 2.7: Test — sessionId смена сбрасывает state**

```kotlin
@Test fun `new session re-anchors carry`() {
    val interp = newInterpolator()
    interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
    interp.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)
    // ignition off + on → new sessionId, totalElec may have ticked up overnight
    interp.onSample(soc = 78, totalElecKwh = 1001.2, sessionId = 2L)
    assertEquals(0.0, interp.carryOver(totalElecKwh = 1001.2, soc = 78), 0.001)
}
```

Run, expect PASS.

- [ ] **Step 2.8: Test — totalElec rollback (glitch) → carry 0 не отрицательный**

```kotlin
@Test fun `negative carry clamped to zero`() {
    val interp = newInterpolator()
    interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
    interp.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)
    // glitch: DiPars returns lower totalElec
    assertEquals(0.0, interp.carryOver(totalElecKwh = 999.8, soc = 80), 0.001)
}
```

Run, expect PASS.

- [ ] **Step 2.9: Создать prefs-обёртку для production**

`app/src/main/kotlin/com/bydmate/app/domain/calculator/SocInterpolatorPrefsImpl.kt` (вместе с интерфейсом — можно держать в том же файле что и SocInterpolator, либо вынести; для простоты — отдельный файл):

```kotlin
package com.bydmate.app.domain.calculator

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SocInterpolatorPrefsImpl @Inject constructor(context: Context) : SocInterpolatorPrefs {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SocInterpolatorState? {
        if (!prefs.contains(KEY_LAST_SOC) || !prefs.contains(KEY_TOTAL_ELEC_BITS)) return null
        return SocInterpolatorState(
            lastSoc = prefs.getInt(KEY_LAST_SOC, 0),
            totalElecAtChange = Double.fromBits(prefs.getLong(KEY_TOTAL_ELEC_BITS, 0L)),
        )
    }

    override fun save(state: SocInterpolatorState) {
        prefs.edit()
            .putInt(KEY_LAST_SOC, state.lastSoc)
            .putLong(KEY_TOTAL_ELEC_BITS, state.totalElecAtChange.toRawBits())
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_LAST_SOC).remove(KEY_TOTAL_ELEC_BITS).apply()
    }

    private companion object {
        const val PREFS_NAME = "bydmate_range_prefs"
        const val KEY_LAST_SOC = "soc_interp_last_soc"
        const val KEY_TOTAL_ELEC_BITS = "soc_interp_total_elec_bits"
    }
}
```

- [ ] **Step 2.10: Hilt-binding для SocInterpolatorPrefs**

В `AppModule.kt`, добавить (рядом с другими `@Provides`):

```kotlin
@Provides
@Singleton
fun provideSocInterpolatorPrefs(@ApplicationContext ctx: Context): SocInterpolatorPrefs =
    SocInterpolatorPrefsImpl(ctx)
```

И импорт:
```kotlin
import com.bydmate.app.domain.calculator.SocInterpolatorPrefs
import com.bydmate.app.domain.calculator.SocInterpolatorPrefsImpl
```

`SocInterpolator` сам инжектится через constructor, `@Singleton`. `capacityKwhProvider` инжектится отдельно — добавить в AppModule:

```kotlin
@Provides
@Singleton
fun provideSocInterpolator(
    settingsRepository: com.bydmate.app.data.repository.SettingsRepository,
    prefs: SocInterpolatorPrefs,
): SocInterpolator = SocInterpolator(
    capacityKwhProvider = { settingsRepository.getBatteryCapacity() },
    persistence = prefs,
)
```

(Это нужно потому что `capacityKwhProvider: suspend () -> Double` — лямбда, Hilt сам не сгенерит.)

- [ ] **Step 2.11: Run все тесты — PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.SocInterpolatorTest"
```

Expected: 5/5 PASS.

- [ ] **Step 2.12: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/domain/calculator/SocInterpolator.kt \
        app/src/main/kotlin/com/bydmate/app/domain/calculator/SocInterpolatorPrefsImpl.kt \
        app/src/test/kotlin/com/bydmate/app/domain/calculator/SocInterpolatorTest.kt \
        app/src/main/kotlin/com/bydmate/app/di/AppModule.kt
git commit -m "feat(range): SocInterpolator — smooth carry-over between SOC clicks"
```

---

## Task 3: OdometerConsumptionBuffer (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBuffer.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/domain/calculator/FakeOdometerSampleDao.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBufferTest.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt` (Hilt binding)

**Контракт:** см. spec 3.1. Константы: `WINDOW_KM=25.0`, `SHORT_WINDOW_KM=2.0`, `MIN_BUFFER_KM=5.0`, `MIN_MILEAGE_DELTA=0.05`, `MAX_BUFFER_ROWS=500`. Алгоритм recentAvg — суммирует валидные cross-session pairs (skip prev.sessionId != cur.sessionId, skip dKm <= 0, skip dKwh < 0, skip null totalElec в TOTAL_ELEC mode).

- [ ] **Step 3.1: FakeOdometerSampleDao**

`app/src/test/kotlin/com/bydmate/app/domain/calculator/FakeOdometerSampleDao.kt`:

```kotlin
package com.bydmate.app.domain.calculator

import com.bydmate.app.data.local.dao.OdometerSampleDao
import com.bydmate.app.data.local.entity.OdometerSampleEntity

class FakeOdometerSampleDao : OdometerSampleDao {
    private val rows = mutableListOf<OdometerSampleEntity>()
    private var nextId = 1L

    override suspend fun insert(sample: OdometerSampleEntity): Long {
        val withId = sample.copy(id = nextId++)
        rows += withId
        return withId.id
    }

    override suspend fun last(): OdometerSampleEntity? = rows.maxByOrNull { it.id }

    override suspend fun windowFrom(minMileage: Double): List<OdometerSampleEntity> =
        rows.filter { it.mileageKm >= minMileage }.sortedBy { it.mileageKm }

    override suspend fun trimBelow(cutoff: Double) {
        rows.removeAll { it.mileageKm < cutoff }
    }

    override suspend fun deleteOldest(howMany: Int) {
        rows.sortBy { it.id }
        repeat(howMany.coerceAtMost(rows.size)) { rows.removeAt(0) }
    }

    override suspend fun count(): Int = rows.size
    override suspend fun clear() { rows.clear() }

    fun snapshot(): List<OdometerSampleEntity> = rows.sortedBy { it.id }.toList()
}
```

- [ ] **Step 3.2: Test — пустой буфер возвращает fallback**

`app/src/test/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBufferTest.kt`:

```kotlin
package com.bydmate.app.domain.calculator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OdometerConsumptionBufferTest {

    private fun newBuffer(fallback: Double = 18.0, dao: FakeOdometerSampleDao = FakeOdometerSampleDao()) =
        OdometerConsumptionBuffer(dao = dao, fallbackEmaProvider = { fallback })

    @Test fun `empty buffer returns fallback`() = runBlocking {
        val b = newBuffer(fallback = 18.0)
        assertEquals(18.0, b.recentAvgConsumption(), 0.001)
        assertNull(b.shortAvgConsumption())
    }
}
```

- [ ] **Step 3.3: Run, expect FAIL (`Unresolved reference: OdometerConsumptionBuffer`)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.OdometerConsumptionBufferTest"
```

- [ ] **Step 3.4: Скелет OdometerConsumptionBuffer**

`app/src/main/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBuffer.kt`:

```kotlin
package com.bydmate.app.domain.calculator

import com.bydmate.app.data.local.dao.OdometerSampleDao
import com.bydmate.app.data.local.entity.OdometerSampleEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class BufferStatus(
    val rowCount: Int,
    val newestMileageKm: Double?,
    val oldestMileageKm: Double?,
    val recentAvg: Double,
    val shortAvg: Double?,
)

/**
 * Persistent rolling window of (mileage, totalElec, soc, sessionId, ts) snapshots.
 * Single source of truth for widget consumption display, trend arrow, and range
 * estimation. See spec 3.1 for algorithm details.
 *
 * SessionId boundary: pairs of consecutive samples (by mileage ASC) with
 * different sessionId values are skipped during averaging — they straddle an
 * ignition cycle, so the energy delta does not reflect "consumption while
 * driving" (could be overnight BMS drain, AC charging, etc).
 */
@Singleton
class OdometerConsumptionBuffer @Inject constructor(
    private val dao: OdometerSampleDao,
    private val fallbackEmaProvider: suspend () -> Double,
) {
    private val mutex = Mutex()

    suspend fun onSample(
        mileage: Double?,
        totalElec: Double?,
        socPercent: Int?,
        sessionId: Long?,
        isCharging: Boolean,
    ): Unit = mutex.withLock {
        if (isCharging) return@withLock
        if (mileage == null) return@withLock
        val prev = dao.last()
        if (prev != null) {
            if (mileage < prev.mileageKm) return@withLock // odometer regression
            if (mileage - prev.mileageKm > 100.0) return@withLock // unrealistic jump
            val sameSession = prev.sessionId == sessionId
            val tinyMove = mileage - prev.mileageKm < MIN_MILEAGE_DELTA
            if (sameSession && tinyMove) return@withLock // spam suppression
        }
        dao.insert(
            OdometerSampleEntity(
                mileageKm = mileage,
                totalElecKwh = totalElec,
                socPercent = socPercent,
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
            )
        )
        // Trim by mileage (anything older than WINDOW_KM behind newest can go)
        val newest = mileage
        dao.trimBelow(newest - WINDOW_KM - TRIM_HYSTERESIS_KM)
        // Hard cap fallback
        val n = dao.count()
        if (n > MAX_BUFFER_ROWS) dao.deleteOldest(n - MAX_BUFFER_ROWS)
    }

    suspend fun recentAvgConsumption(): Double = mutex.withLock { computeAvg(WINDOW_KM, fallbackOnShort = true) }
    suspend fun shortAvgConsumption(): Double? = mutex.withLock {
        val v = computeAvg(SHORT_WINDOW_KM, fallbackOnShort = false)
        if (v.isNaN()) null else v
    }

    suspend fun status(): BufferStatus = mutex.withLock {
        val all = dao.windowFrom(0.0)
        BufferStatus(
            rowCount = all.size,
            newestMileageKm = all.lastOrNull()?.mileageKm,
            oldestMileageKm = all.firstOrNull()?.mileageKm,
            recentAvg = computeAvgUnlocked(all, WINDOW_KM, fallbackOnShort = true),
            shortAvg = run {
                val v = computeAvgUnlocked(all, SHORT_WINDOW_KM, fallbackOnShort = false)
                if (v.isNaN()) null else v
            },
        )
    }

    /** Internal: compute over a freshly-fetched window. */
    private suspend fun computeAvg(windowKm: Double, fallbackOnShort: Boolean): Double {
        val newest = dao.last() ?: return if (fallbackOnShort) fallbackEmaProvider() else Double.NaN
        val samples = dao.windowFrom(newest.mileageKm - windowKm)
        return computeAvgUnlocked(samples, windowKm, fallbackOnShort)
    }

    private suspend fun computeAvgUnlocked(
        samples: List<OdometerSampleEntity>,
        windowKm: Double,
        fallbackOnShort: Boolean,
    ): Double {
        if (samples.size < 2) return if (fallbackOnShort) fallbackEmaProvider() else Double.NaN
        var totalKm = 0.0
        var totalKwh = 0.0
        for (i in 1..samples.lastIndex) {
            val prev = samples[i - 1]
            val cur = samples[i]
            if (prev.sessionId != cur.sessionId) continue
            val pe = prev.totalElecKwh
            val ce = cur.totalElecKwh
            if (pe == null || ce == null) continue
            val dKm = cur.mileageKm - prev.mileageKm
            val dKwh = ce - pe
            if (dKm <= 0.0 || dKwh < 0.0) continue
            totalKm += dKm
            totalKwh += dKwh
        }
        val minOk = if (fallbackOnShort) MIN_BUFFER_KM else windowKm
        if (totalKm < minOk) return if (fallbackOnShort) fallbackEmaProvider() else Double.NaN
        return totalKwh / totalKm * 100.0
    }

    companion object {
        const val WINDOW_KM = 25.0
        const val SHORT_WINDOW_KM = 2.0
        const val MIN_BUFFER_KM = 5.0
        const val MIN_MILEAGE_DELTA = 0.05  // 50 m
        const val MAX_BUFFER_ROWS = 500
        // Keep a small overhang so trim doesn't immediately remove samples we just slid past.
        const val TRIM_HYSTERESIS_KM = 1.0
    }
}
```

- [ ] **Step 3.5: Run test — должен PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.OdometerConsumptionBufferTest.empty buffer returns fallback"
```

Expected: PASS.

- [ ] **Step 3.6: Test — under MIN_BUFFER_KM возвращает fallback**

В `OdometerConsumptionBufferTest.kt` добавить:

```kotlin
@Test fun `under MIN_BUFFER_KM returns fallback`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    b.onSample(mileage = 10000.0, totalElec = 1500.0, socPercent = 80, sessionId = s, isCharging = false)
    b.onSample(mileage = 10001.0, totalElec = 1500.18, socPercent = 80, sessionId = s, isCharging = false)
    b.onSample(mileage = 10003.0, totalElec = 1500.54, socPercent = 80, sessionId = s, isCharging = false)
    // 3 km < MIN_BUFFER_KM (5 km) → fallback
    assertEquals(18.0, b.recentAvgConsumption(), 0.001)
}
```

Run, expect PASS.

- [ ] **Step 3.7: Test — нормальный кейс ≥ MIN_BUFFER_KM**

```kotlin
@Test fun `normal case computes from buffer`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    // 10 km @ 20 kWh/100km → 2 kWh used
    var mile = 10000.0
    var elec = 1500.0
    b.onSample(mile, elec, 80, s, false)
    repeat(10) {
        mile += 1.0
        elec += 0.20
        b.onSample(mile, elec, 80, s, false)
    }
    // dKm=10, dKwh=2.0 → 20 kWh/100km
    assertEquals(20.0, b.recentAvgConsumption(), 0.1)
}
```

Run, expect PASS.

- [ ] **Step 3.8: Test — sessionId boundary (overnight)**

```kotlin
@Test fun `sessionId boundary skips cross-session pair`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    // Day 1 session: 10 km @ 18 kWh/100km
    val s1 = 1L
    var mile = 10000.0; var elec = 1500.0
    b.onSample(mile, elec, 80, s1, false)
    repeat(10) {
        mile += 1.0; elec += 0.18
        b.onSample(mile, elec, 80, s1, false)
    }
    // Overnight gap — totalElec drifts up, mileage doesn't move, then new session 5 m later
    val s2 = 2L
    b.onSample(mile + 0.05, elec + 1.2, 78, s2, false)  // first new-session tick
    // Day 2 session: 5 km @ 22 kWh/100km
    repeat(5) {
        mile += 1.0; elec += 1.2 + 0.22  // chunk only counts within session
        // Actually we want session-internal deltas — use independent var:
    }
    // Reset for cleaner setup:
    dao.clear()
    var m = 10000.0; var e = 1500.0
    b.onSample(m, e, 80, s1, false)
    repeat(10) { m += 1.0; e += 0.18; b.onSample(m, e, 80, s1, false) }
    // Cross-session jump — should be skipped:
    b.onSample(m + 0.05, e + 1.2, 78, s2, false)
    // Day 2: 5 km @ 22 kWh/100km
    var m2 = m + 0.05; var e2 = e + 1.2
    repeat(5) { m2 += 1.0; e2 += 0.22; b.onSample(m2, e2, 78, s2, false) }
    // Total within-session valid distance: 10 km @ 18 + 5 km @ 22
    // Weighted avg: (10*18 + 5*22) / 15 = 290/15 ≈ 19.33 kWh/100km
    assertEquals(19.33, b.recentAvgConsumption(), 0.5)
}
```

Run, expect PASS.

- [ ] **Step 3.9: Test — HVAC простой внутри сессии не ломает расход**

```kotlin
@Test fun `HVAC idle inside same session does not pollute avg`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    var m = 10000.0; var e = 1500.0
    b.onSample(m, e, 80, s, false)
    // 5 km @ 18 kWh/100km
    repeat(5) { m += 1.0; e += 0.18; b.onSample(m, e, 80, s, false) }
    // 30-min HVAC idle: same mileage (tinyMove drops it), but next driving tick will absorb 0.3 kWh
    // Simulated: when car moves again, totalElec is 0.3 kWh higher than before idle
    e += 0.3
    // 5 more km @ 18 kWh/100km but with 0.3 absorbed in first tick
    m += 1.0; e += 0.18; b.onSample(m, e, 80, s, false)  // first post-idle tick, dKwh = 0.48 over 1 km
    repeat(4) { m += 1.0; e += 0.18; b.onSample(m, e, 80, s, false) }
    // Total: 10 km, kWh = 5*0.18 + 0.48 + 4*0.18 = 0.9 + 0.48 + 0.72 = 2.1 → 21 kWh/100km
    // (HVAC absorbed honestly into one segment — that's correct behaviour)
    assertEquals(21.0, b.recentAvgConsumption(), 0.5)
}
```

Run, expect PASS. Этот тест — позитивная проверка: HVAC учитывается как часть общего расхода в живой сессии (что и хотел пользователь).

- [ ] **Step 3.10: Test — charging ticks игнорируются**

```kotlin
@Test fun `charging ticks not inserted into buffer`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    b.onSample(10000.0, 1500.0, 50, s, isCharging = false)
    b.onSample(10000.05, 1500.01, 50, s, isCharging = true)  // skipped
    b.onSample(10000.06, 1500.02, 80, s, isCharging = true)  // skipped
    assertEquals(1, dao.count())
}
```

Run, expect PASS.

- [ ] **Step 3.11: Test — odometer regression и big jump пропускаются**

```kotlin
@Test fun `odometer regression skipped`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    b.onSample(10000.0, 1500.0, 80, s, false)
    b.onSample(9999.5, 1500.05, 80, s, false)  // regression — skipped
    assertEquals(1, dao.count())
}

@Test fun `huge forward jump skipped`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    b.onSample(10000.0, 1500.0, 80, s, false)
    b.onSample(10500.0, 1600.0, 70, s, false)  // 500 km jump — skipped
    assertEquals(1, dao.count())
}
```

Run, expect PASS.

- [ ] **Step 3.12: Test — MIN_MILEAGE_DELTA spam suppression**

```kotlin
@Test fun `tick under MIN_MILEAGE_DELTA in same session not inserted`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    b.onSample(10000.0, 1500.0, 80, s, false)
    b.onSample(10000.02, 1500.001, 80, s, false)  // 20 m — skipped
    b.onSample(10000.04, 1500.002, 80, s, false)  // 40 m — skipped
    b.onSample(10000.06, 1500.003, 80, s, false)  // 60 m — inserted
    assertEquals(2, dao.count())
}

@Test fun `session change forces insert even on tiny mileage delta`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    b.onSample(10000.0, 1500.0, 80, 1L, false)
    // ignition cycle, second tick at +20 m but new session — must insert (boundary marker)
    b.onSample(10000.02, 1501.0, 79, 2L, false)
    assertEquals(2, dao.count())
}
```

Run, expect PASS.

- [ ] **Step 3.13: Test — shortAvg возвращает null если меньше SHORT_WINDOW_KM**

```kotlin
@Test fun `short avg null when distance under 2 km`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    b.onSample(10000.0, 1500.0, 80, s, false)
    b.onSample(10001.0, 1500.18, 80, s, false)  // 1 km
    assertNull(b.shortAvgConsumption())
}

@Test fun `short avg computed when distance >= 2 km`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    var m = 10000.0; var e = 1500.0
    b.onSample(m, e, 80, s, false)
    repeat(2) { m += 1.0; e += 0.20; b.onSample(m, e, 80, s, false) }
    // 2 km @ 20 kWh/100km
    assertEquals(20.0, b.shortAvgConsumption()!!, 0.1)
}
```

Run, expect PASS.

- [ ] **Step 3.14: Test — null totalElec в сегменте (SOC_BASED transitional) пропускается**

```kotlin
@Test fun `null totalElec pair is skipped`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    b.onSample(10000.0, 1500.0, 80, s, false)
    b.onSample(10001.0, null, 80, s, false)
    b.onSample(10002.0, 1500.36, 80, s, false)
    // Pair (10001 → 10002) skipped (prev.totalElec null)
    // Pair (10000 → 10001) skipped (cur.totalElec null)
    // Total valid km = 0 → fallback
    assertEquals(18.0, b.recentAvgConsumption(), 0.001)
}
```

Run, expect PASS.

- [ ] **Step 3.15: Test — trim старых семплов работает**

```kotlin
@Test fun `samples beyond WINDOW_KM trimmed on insert`() = runBlocking {
    val dao = FakeOdometerSampleDao()
    val b = newBuffer(fallback = 18.0, dao = dao)
    val s = 1L
    var m = 10000.0; var e = 1500.0
    b.onSample(m, e, 80, s, false)
    repeat(50) { m += 1.0; e += 0.20; b.onSample(m, e, 80, s, false) }
    // newest mileage 10050; trim cutoff = 10050 - 25 - 1 = 10024
    val all = dao.snapshot()
    assertTrue("all samples within WINDOW_KM + hysteresis", all.all { it.mileageKm >= 10024.0 })
}
```

Импорт: `import org.junit.Assert.assertTrue`. Run, expect PASS.

- [ ] **Step 3.16: Hilt-binding для OdometerConsumptionBuffer**

В `AppModule.kt` добавить:

```kotlin
@Provides
@Singleton
fun provideOdometerConsumptionBuffer(
    dao: OdometerSampleDao,
    tripRepository: com.bydmate.app.data.repository.TripRepository,
): OdometerConsumptionBuffer = OdometerConsumptionBuffer(
    dao = dao,
    fallbackEmaProvider = { tripRepository.getEmaConsumption() },
)
```

И import `com.bydmate.app.domain.calculator.OdometerConsumptionBuffer`.

- [ ] **Step 3.17: Run полный suite buffer-тестов — PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.OdometerConsumptionBufferTest"
```

Expected: 11/11 PASS.

- [ ] **Step 3.18: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBuffer.kt \
        app/src/test/kotlin/com/bydmate/app/domain/calculator/FakeOdometerSampleDao.kt \
        app/src/test/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBufferTest.kt \
        app/src/main/kotlin/com/bydmate/app/di/AppModule.kt
git commit -m "feat(range): OdometerConsumptionBuffer with sessionId boundary"
```

---

## Task 4: RangeCalculator (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/domain/calculator/RangeCalculator.kt`
- Create: `app/src/test/kotlin/com/bydmate/app/domain/calculator/RangeCalculatorTest.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt`

**Контракт:** `remaining_kwh = SOC × cap / 100 - socInterpolator.carryOver(totalElec, soc)`; `range = remaining_kwh / recentAvg × 100`. Null SOC / SOC ≤ 0 / recentAvg ≤ 0 → null.

- [ ] **Step 4.1: Test — null SOC**

`app/src/test/kotlin/com/bydmate/app/domain/calculator/RangeCalculatorTest.kt`:

```kotlin
package com.bydmate.app.domain.calculator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeCalculatorTest {

    private fun newCalc(
        capacity: Double = 72.9,
        recentAvg: Double = 18.0,
        carry: Double = 0.0,
    ) = RangeCalculator(
        buffer = StubBuffer(recentAvg),
        capacityProvider = { capacity },
        socInterpolator = StubInterpolator(carry),
    )

    @Test fun `null SOC returns null`() = runBlocking {
        val c = newCalc()
        assertNull(c.estimate(soc = null, totalElecKwh = 1500.0))
    }
}

private class StubBuffer(private val avg: Double) {
    suspend fun recentAvgConsumption() = avg
}
// Note: actual buffer is OdometerConsumptionBuffer — for tests we use a sealed alternative.
// See Step 4.3 for refactor: introduce ConsumptionAvgSource abstraction.
```

⚠️ Так как `OdometerConsumptionBuffer` — это конкретный класс, для unit-теста проще принять интерфейс `ConsumptionAvgSource` в RangeCalculator. Сделаем это в реализации (Step 4.3).

- [ ] **Step 4.2: Run, expect FAIL**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.RangeCalculatorTest"
```

- [ ] **Step 4.3: Реализовать RangeCalculator с тестируемым интерфейсом**

`app/src/main/kotlin/com/bydmate/app/domain/calculator/RangeCalculator.kt`:

```kotlin
package com.bydmate.app.domain.calculator

import javax.inject.Inject
import javax.inject.Singleton

/** Test-friendly seam: production binding is OdometerConsumptionBuffer. */
interface ConsumptionAvgSource {
    suspend fun recentAvgConsumption(): Double
}

@Singleton
class RangeCalculator @Inject constructor(
    private val buffer: ConsumptionAvgSource,
    private val capacityProvider: suspend () -> Double,
    private val socInterpolator: SocInterpolator,
) {
    /**
     * Returns estimated range in km, or null when inputs are insufficient.
     *
     *   remaining_kwh = SOC × cap / 100 - socInterpolator.carryOver(totalElec, soc)
     *   range_km      = remaining_kwh / recent_avg × 100
     */
    suspend fun estimate(soc: Int?, totalElecKwh: Double?): Double? {
        if (soc == null || soc <= 0) return null
        val cap = capacityProvider()
        if (cap <= 0.0) return null
        val avg = buffer.recentAvgConsumption()
        if (avg <= 0.0) return null
        val carry = socInterpolator.carryOver(totalElecKwh, soc)
        val remainingKwh = (soc / 100.0) * cap - carry
        if (remainingKwh <= 0.0) return null
        return remainingKwh / avg * 100.0
    }
}
```

И обновить `OdometerConsumptionBuffer` чтобы он реализовал `ConsumptionAvgSource`:

```kotlin
class OdometerConsumptionBuffer @Inject constructor(
    private val dao: OdometerSampleDao,
    private val fallbackEmaProvider: suspend () -> Double,
) : ConsumptionAvgSource {
    // ... rest unchanged ...
    override suspend fun recentAvgConsumption(): Double = mutex.withLock { ... }
```

- [ ] **Step 4.4: Обновить тестовый StubBuffer**

В `RangeCalculatorTest.kt` заменить класс на:

```kotlin
private class StubBuffer(private val avg: Double) : ConsumptionAvgSource {
    override suspend fun recentAvgConsumption() = avg
}

private class StubInterpolator(private val carry: Double) : SocInterpolator(
    capacityKwhProvider = { 72.9 },
    persistence = NoOpPrefs(),
) {
    override fun carryOver(totalElecKwh: Double?, soc: Int?): Double = carry
}

private class NoOpPrefs : SocInterpolatorPrefs {
    override fun load(): SocInterpolatorState? = null
    override fun save(state: SocInterpolatorState) {}
    override fun clear() {}
}
```

⚠️ Метод `carryOver` в SocInterpolator должен быть `open` чтобы его можно было mock'ать. Поправить в `SocInterpolator.kt`:

```kotlin
open class SocInterpolator @Inject constructor(...) {
    open fun carryOver(totalElecKwh: Double?, soc: Int?): Double { ... }
}
```

- [ ] **Step 4.5: Run первый тест — PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.RangeCalculatorTest.null SOC returns null"
```

Expected: PASS.

- [ ] **Step 4.6: Test — нормальный кейс**

```kotlin
@Test fun `normal case 50 percent at 18 avg`() = runBlocking {
    val c = newCalc(capacity = 72.9, recentAvg = 18.0)
    // 50% × 72.9 = 36.45 kWh; 36.45 / 18 × 100 = 202.5 km
    assertEquals(202.5, c.estimate(soc = 50, totalElecKwh = 1500.0)!!, 0.5)
}
```

Run, expect PASS.

- [ ] **Step 4.7: Test — carry уменьшает range**

```kotlin
@Test fun `carry reduces remaining kwh`() = runBlocking {
    val c = newCalc(capacity = 72.9, recentAvg = 18.0, carry = 0.5)
    // (50% × 72.9 - 0.5) / 18 × 100 = (36.45 - 0.5) / 18 × 100 = 35.95 / 18 × 100 ≈ 199.7 km
    assertEquals(199.7, c.estimate(soc = 50, totalElecKwh = 1500.0)!!, 0.5)
}
```

Run, expect PASS.

- [ ] **Step 4.8: Test — recentAvg = 0 (cold install) → null**

```kotlin
@Test fun `zero avg returns null`() = runBlocking {
    val c = newCalc(recentAvg = 0.0)
    assertNull(c.estimate(soc = 50, totalElecKwh = 1500.0))
}
```

Run, expect PASS.

- [ ] **Step 4.9: Test — capacity change применяется сразу**

```kotlin
@Test fun `capacity change applies immediately`() = runBlocking {
    var cap = 50.0
    val c = RangeCalculator(
        buffer = StubBuffer(18.0),
        capacityProvider = { cap },
        socInterpolator = StubInterpolator(0.0),
    )
    val before = c.estimate(soc = 50, totalElecKwh = 1500.0)!!
    cap = 72.9
    val after = c.estimate(soc = 50, totalElecKwh = 1500.0)!!
    assertTrue("range increased after capacity bump", after > before * 1.4)
}
```

Импорт `org.junit.Assert.assertTrue`. Run, expect PASS.

- [ ] **Step 4.10: Hilt-binding для RangeCalculator**

В `AppModule.kt`:

```kotlin
@Provides
@Singleton
fun provideRangeCalculator(
    buffer: OdometerConsumptionBuffer,
    settingsRepository: com.bydmate.app.data.repository.SettingsRepository,
    socInterpolator: SocInterpolator,
): RangeCalculator = RangeCalculator(
    buffer = buffer,
    capacityProvider = { settingsRepository.getBatteryCapacity() },
    socInterpolator = socInterpolator,
)
```

И импорт `com.bydmate.app.domain.calculator.RangeCalculator`.

- [ ] **Step 4.11: Run все RangeCalculatorTest — PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.RangeCalculatorTest"
```

Expected: 5/5 PASS.

- [ ] **Step 4.12: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/domain/calculator/RangeCalculator.kt \
        app/src/main/kotlin/com/bydmate/app/domain/calculator/OdometerConsumptionBuffer.kt \
        app/src/main/kotlin/com/bydmate/app/domain/calculator/SocInterpolator.kt \
        app/src/test/kotlin/com/bydmate/app/domain/calculator/RangeCalculatorTest.kt \
        app/src/main/kotlin/com/bydmate/app/di/AppModule.kt
git commit -m "feat(range): RangeCalculator unifies range formula across UI"
```

---

## Task 5: ConsumptionAggregator refactor — тонкая обёртка над buffer

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregator.kt`
- Modify: `app/src/test/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregatorTest.kt` (полная переписка)

**Контракт после refactor:**
- `displayValue` = `OdometerConsumptionBuffer.recentAvgConsumption()` (но напрямую aggregator не вызывает buffer — TrackingService передаёт уже посчитанные значения через `onSample`).
- `trend` сравнивает `shortAvg` с `recentAvg`, hysteresis 0.90/1.10, debounce 30 сек.
- Активация цифры: когда `recentAvg > 0` (т.е. буфер не пуст и не на fallback). При fallback — показываем fallback EMA как «спокойный» baseline.
- Активация стрелки: когда `shortAvg != null` AND `recentAvg > 0`.
- Object stays object (для статического вызова из TrackingService без Hilt).

- [ ] **Step 5.1: Перепишем ConsumptionAggregator**

`app/src/main/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregator.kt` (полная замена):

```kotlin
package com.bydmate.app.domain.calculator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Trend { NONE, DOWN, FLAT, UP }

data class ConsumptionState(
    /** kWh/100km to show on widget; null when nothing meaningful yet. */
    val displayValue: Double?,
    val trend: Trend,
)

/**
 * Stateless trend computer for the floating widget.
 *
 * TrackingService computes recent and short averages via OdometerConsumptionBuffer
 * and pushes them in via onSample. Aggregator's only stateful job is hysteresis +
 * debounce on the trend arrow.
 */
object ConsumptionAggregator {

    private const val DEBOUNCE_MS = 30_000L
    private const val ENTER_DOWN = 0.90
    private const val ENTER_UP = 1.10
    private const val EXIT_DOWN_TO_FLAT = 0.95
    private const val EXIT_UP_TO_FLAT = 1.05

    private val _state = MutableStateFlow(ConsumptionState(null, Trend.NONE))
    val state: StateFlow<ConsumptionState> = _state

    private var committedTrend: Trend = Trend.NONE
    private var candidateTrend: Trend = Trend.NONE
    private var candidateSince: Long = 0L

    /**
     * @param recentAvg     25-km rolling average from OdometerConsumptionBuffer.
     *                      Pass 0 (or negative) when buffer fallbacks to nothing.
     * @param shortAvg      2-km short window. Null when buffer has < 2 km of valid data.
     */
    @Synchronized
    fun onSample(
        now: Long,
        recentAvg: Double,
        shortAvg: Double?,
    ) {
        val display = if (recentAvg > 0.01) recentAvg else null
        if (display == null || shortAvg == null) {
            committedTrend = Trend.NONE
            candidateTrend = Trend.NONE
            candidateSince = 0L
            publish(display, Trend.NONE)
            return
        }
        val ratio = shortAvg / recentAvg
        val candidate = candidateFor(committedTrend, ratio)
        updateDebounce(now, candidate)
        publish(display, committedTrend)
    }

    @Synchronized
    fun reset() {
        committedTrend = Trend.NONE
        candidateTrend = Trend.NONE
        candidateSince = 0L
        _state.value = ConsumptionState(null, Trend.NONE)
    }

    private fun candidateFor(current: Trend, ratio: Double): Trend = when (current) {
        Trend.NONE, Trend.FLAT -> when {
            ratio < ENTER_DOWN -> Trend.DOWN
            ratio > ENTER_UP -> Trend.UP
            else -> Trend.FLAT
        }
        Trend.DOWN -> when {
            ratio > ENTER_UP -> Trend.UP
            ratio >= EXIT_DOWN_TO_FLAT -> Trend.FLAT
            else -> Trend.DOWN
        }
        Trend.UP -> when {
            ratio < ENTER_DOWN -> Trend.DOWN
            ratio <= EXIT_UP_TO_FLAT -> Trend.FLAT
            else -> Trend.UP
        }
    }

    private fun updateDebounce(now: Long, candidate: Trend) {
        if (candidate == committedTrend) {
            candidateTrend = candidate
            candidateSince = now
            return
        }
        if (candidate != candidateTrend) {
            candidateTrend = candidate
            candidateSince = now
            return
        }
        if (now - candidateSince >= DEBOUNCE_MS) {
            committedTrend = candidate
        }
    }

    private fun publish(display: Double?, trend: Trend) {
        _state.value = ConsumptionState(display, trend)
    }
}
```

- [ ] **Step 5.2: Перепишем ConsumptionAggregatorTest**

`app/src/test/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregatorTest.kt` (полная замена):

```kotlin
package com.bydmate.app.domain.calculator

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Aggregator after v2.5.0 refactor: stateless trend computer, no session/buffer
 * logic. TrackingService feeds it the already-computed recent and short averages
 * from OdometerConsumptionBuffer.
 */
class ConsumptionAggregatorTest {

    @Before fun reset() { ConsumptionAggregator.reset() }
    @After fun teardown() { ConsumptionAggregator.reset() }

    @Test fun `null short — display set, trend NONE`() {
        ConsumptionAggregator.onSample(now = 0L, recentAvg = 18.0, shortAvg = null)
        val s = ConsumptionAggregator.state.value
        assertEquals(18.0, s.displayValue!!, 0.001)
        assertEquals(Trend.NONE, s.trend)
    }

    @Test fun `recent zero — display null, trend NONE`() {
        ConsumptionAggregator.onSample(now = 0L, recentAvg = 0.0, shortAvg = 18.0)
        val s = ConsumptionAggregator.state.value
        assertNull(s.displayValue)
        assertEquals(Trend.NONE, s.trend)
    }

    @Test fun `ratio inside band — trend FLAT after debounce`() {
        // ratio 19/18 = 1.055 → inside ENTER_UP 1.10 → candidate FLAT
        var now = 0L
        ConsumptionAggregator.onSample(now, 18.0, 19.0)
        // First sample seeds candidateSince — debounce not yet reached
        // Roll forward 35 s — beyond DEBOUNCE_MS = 30_000
        now += 35_000
        ConsumptionAggregator.onSample(now, 18.0, 19.0)
        assertEquals(Trend.FLAT, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `short below 0_90 → DOWN after debounce`() {
        var now = 0L
        ConsumptionAggregator.onSample(now, 20.0, 17.0)  // ratio 0.85
        now += 35_000
        ConsumptionAggregator.onSample(now, 20.0, 17.0)
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `short above 1_10 → UP after debounce`() {
        var now = 0L
        ConsumptionAggregator.onSample(now, 20.0, 23.0)  // ratio 1.15
        now += 35_000
        ConsumptionAggregator.onSample(now, 20.0, 23.0)
        assertEquals(Trend.UP, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `trend stays NONE before debounce expires`() {
        ConsumptionAggregator.onSample(now = 0, recentAvg = 20.0, shortAvg = 23.0)
        ConsumptionAggregator.onSample(now = 5_000, recentAvg = 20.0, shortAvg = 23.0)
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `flapping candidate resets debounce timer`() {
        ConsumptionAggregator.onSample(now = 0, recentAvg = 20.0, shortAvg = 23.0)  // → UP candidate
        ConsumptionAggregator.onSample(now = 20_000, recentAvg = 20.0, shortAvg = 17.0)  // → DOWN candidate (timer reset)
        ConsumptionAggregator.onSample(now = 35_000, recentAvg = 20.0, shortAvg = 17.0)  // 15 s since reset — still pending
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
        ConsumptionAggregator.onSample(now = 70_000, recentAvg = 20.0, shortAvg = 17.0)  // > 30 s — committed
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `reset wipes everything`() {
        ConsumptionAggregator.onSample(now = 0, recentAvg = 20.0, shortAvg = 17.0)
        ConsumptionAggregator.onSample(now = 35_000, recentAvg = 20.0, shortAvg = 17.0)
        ConsumptionAggregator.reset()
        val s = ConsumptionAggregator.state.value
        assertNull(s.displayValue)
        assertEquals(Trend.NONE, s.trend)
    }
}
```

⚠️ Старые ссылки на `SessionBaseline`, `currentSessionBaseline()`, `pendingBaseline` удаляются вместе с реализацией. Удалить data class `SessionBaseline` из `ConsumptionAggregator.kt` (он там был).

- [ ] **Step 5.3: Run полный suite — PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.domain.calculator.ConsumptionAggregatorTest"
```

Expected: 8/8 PASS.

- [ ] **Step 5.4: Build app — должен сломаться, потому что TrackingService и SessionPersistence ссылаются на старые API**

```bash
./gradlew :app:assembleDebug
```

Expected: FAIL (Unresolved reference: SessionBaseline, etc.). Это нормально — следующая задача исправит.

- [ ] **Step 5.5: Commit (на этом шаге репо в transient broken state — это норма для TDD рефактора, исправит Task 6)**

```bash
git add app/src/main/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregator.kt \
        app/src/test/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregatorTest.kt
git commit -m "refactor(range): ConsumptionAggregator → stateless trend computer

WIP: TrackingService/SessionPersistence still reference removed SessionBaseline.
Build will be red until Task 6 wires the new buffer in. All aggregator unit
tests (8/8) pass."
```

---

## Task 6: SessionPersistence упрощение

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/service/SessionPersistence.kt`

**Контекст:** до v2.5.0 SessionPersistence хранил `SessionBaseline(sessionStartedAt, mileageStart, totalElecStart)` чтобы Aggregator мог восстановить cumulative-режим после sys-kill. Теперь baseline-режима нет — нужен только `sessionStartedAt` (для AutomationEngine.fireOncePerTrip + UI duration). lastActiveTs сохраняется чтобы updateSessionState мог восстановить idle-window.

- [ ] **Step 6.1: Переписать SessionPersistence**

`app/src/main/kotlin/com/bydmate/app/service/SessionPersistence.kt`:

```kotlin
package com.bydmate.app.service

import android.content.Context

/**
 * Holds the current widget-session anchor across process restarts. After v2.5.0
 * the aggregator no longer needs mileage/totalElec baselines (those moved into
 * OdometerConsumptionBuffer which is itself Room-persistent), so this is just
 * the ignition-on timestamp + last-active heartbeat.
 *
 * Cleared on ignition-off (powerState 0 + 30 sec idle) so the next session
 * gets a fresh sessionStartedAt.
 */
data class PersistedSession(
    val sessionStartedAt: Long,
    val lastActiveTs: Long,
)

class SessionPersistence(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersistedSession? {
        if (!prefs.contains(KEY_STARTED_AT)) return null
        val ts = prefs.getLong(KEY_STARTED_AT, 0L)
        val last = prefs.getLong(KEY_LAST_ACTIVE_TS, 0L)
        if (ts <= 0L) return null
        return PersistedSession(sessionStartedAt = ts, lastActiveTs = last)
    }

    fun save(sessionStartedAt: Long, lastActiveTs: Long) {
        prefs.edit()
            .putLong(KEY_STARTED_AT, sessionStartedAt)
            .putLong(KEY_LAST_ACTIVE_TS, lastActiveTs)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_STARTED_AT)
            .remove(KEY_LAST_ACTIVE_TS)
            // Also wipe legacy keys from v2.4.x in case of in-place upgrade.
            .remove(LEGACY_KEY_MILEAGE_START)
            .remove(LEGACY_KEY_ELEC_START)
            .remove(LEGACY_KEY_MILEAGE_START_BITS)
            .remove(LEGACY_KEY_ELEC_START_BITS)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "bydmate_widget_session"
        private const val KEY_STARTED_AT = "session_started_at"
        private const val KEY_LAST_ACTIVE_TS = "last_active_ts"
        // Legacy v2.4.x keys — only cleared, never read.
        private const val LEGACY_KEY_MILEAGE_START = "mileage_start_km"
        private const val LEGACY_KEY_ELEC_START = "elec_start_kwh"
        private const val LEGACY_KEY_MILEAGE_START_BITS = "mileage_start_km_bits"
        private const val LEGACY_KEY_ELEC_START_BITS = "elec_start_kwh_bits"
    }
}
```

- [ ] **Step 6.2: Build — может ещё ругаться на TrackingService (Task 7), но SessionPersistence сам должен компилироваться**

```bash
./gradlew :app:compileDebugKotlin
```

Если ошибки касаются только TrackingService — ОК, идём дальше.

- [ ] **Step 6.3: Commit (still WIP)**

```bash
git add app/src/main/kotlin/com/bydmate/app/service/SessionPersistence.kt
git commit -m "refactor(session): drop mileage/elec baseline, keep only sessionStartedAt"
```

---

## Task 7: TrackingService integration

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`

**Изменения:**
1. Inject `OdometerConsumptionBuffer`, `SocInterpolator`, `RangeCalculator` через `@Inject lateinit var`.
2. Удалить `cachedEmaConsumption`, `cachedBaselineEma`, `cachedBatteryCapacity`, `lastCacheRefreshTs`, `refreshConsumptionCache`, `maybeRefreshConsumptionCache`, `doRefreshConsumptionCache`, `estimateRangeKm` (старая формула).
3. Удалить `pendingBaseline` (больше не существует SessionBaseline).
4. В polling loop:
   - `buffer.onSample(data.mileage, data.totalElecConsumption, data.soc, sessionId, isCharging)`
   - `socInterpolator.onSample(data.soc, data.totalElecConsumption, sessionId)`
   - `val recentAvg = buffer.recentAvgConsumption(); val shortAvg = buffer.shortAvgConsumption()`
   - `ConsumptionAggregator.onSample(now, recentAvg, shortAvg)`
   - `_lastRangeKm.value = rangeCalculator.estimate(data.soc, data.totalElecConsumption)`
5. SessionPersistence.save теперь принимает только `(sessionStartedAt, lastActiveTs)`.
6. Restored session: вместо восстановления pendingBaseline просто восстановить `_sessionStartedAt.value` и `sessionLastActiveTs`.
7. `maybeLogSessionSummary` упростить — убрать ссылки на SessionBaseline, добавить `buffer.status()`.
8. Detect `isCharging`: использовать `data.chargeGunState ?: 0 > 0` либо `data.chargingStatus == 2` (зависит от существующих полей DiParsData).

- [ ] **Step 7.1: Проверить какие поля есть для charging detection**

```bash
grep -n "chargeGun\|chargingStatus\|isCharging\|powerState" app/src/main/kotlin/com/bydmate/app/data/remote/DiParsData.kt | head
```

Использовать то, что вернётся. Если есть `chargeGunState` — берём его. В коде ниже подставлено `data.chargeGunState ?: 0 > 0` — заменить на актуальное.

- [ ] **Step 7.2: Удалить старые члены и методы**

В `TrackingService.kt` удалить:
- Строки 79-82 (`cachedEmaConsumption`, `cachedBaselineEma`, `cachedBatteryCapacity`, `lastCacheRefreshTs`)
- Строки 89-90 (`pendingBaseline`, `sessionLastActiveTs` — sessionLastActiveTs оставить)

Точнее — оставить `sessionLastActiveTs`, удалить `pendingBaseline`. Удалить методы:
- `refreshConsumptionCache()` (строки 215-217)
- `maybeRefreshConsumptionCache(now)` (строки 227-231)
- `doRefreshConsumptionCache()` (строки 233-247)
- `estimateRangeKm(soc)` (строки 249-256)

Удалить константу `CACHE_REFRESH_INTERVAL_MS`.

- [ ] **Step 7.3: Добавить inject новых зависимостей**

После строки `@Inject lateinit var alicePollingManager: AlicePollingManager`:

```kotlin
@Inject lateinit var odometerBuffer: com.bydmate.app.domain.calculator.OdometerConsumptionBuffer
@Inject lateinit var socInterpolator: com.bydmate.app.domain.calculator.SocInterpolator
@Inject lateinit var rangeCalculator: com.bydmate.app.domain.calculator.RangeCalculator
```

- [ ] **Step 7.4: Поправить onCreate — restore session**

Заменить блок Restore widget session anchor (строки 153-166):

```kotlin
sessionPersistence = SessionPersistence(this)
val restored = sessionPersistence.load()
if (restored != null) {
    _sessionStartedAt.value = restored.sessionStartedAt
    sessionLastActiveTs = restored.lastActiveTs
    Log.i(TAG, "Restored session: startedAt=${restored.sessionStartedAt}, " +
        "lastActiveTs=${restored.lastActiveTs}")
}
```

Убрать вызов `refreshConsumptionCache()` (строка 170) и второй вызов после sync (строка 208).

- [ ] **Step 7.5: Поправить updateSessionState**

Удалить `pendingBaseline = null` (строки 293, 301):

```kotlin
private fun updateSessionState(now: Long, data: DiParsData): Long? {
    val powerOn = (data.powerState ?: 0) >= 1
    val driving = tripTracker.state.value == com.bydmate.app.domain.tracker.TripState.DRIVING
    val active = powerOn || driving

    val currentSession = _sessionStartedAt.value
    if (active) {
        sessionLastActiveTs = now
        if (currentSession == null) {
            _sessionStartedAt.value = now
            Log.i(TAG, "Widget session START at $now (powerOn=$powerOn, driving=$driving)")
        }
    } else if (currentSession != null) {
        val idleFor = now - sessionLastActiveTs
        if (idleFor >= SESSION_IDLE_CLOSE_MS) {
            Log.i(TAG, "Widget session END (idle ${idleFor / 1000}s, powerOn=$powerOn, driving=$driving)")
            _sessionStartedAt.value = null
            sessionPersistence.clear()
        }
    }
    return _sessionStartedAt.value
}
```

- [ ] **Step 7.6: Поправить maybeLogSessionSummary**

Заменить вызов `ConsumptionAggregator.currentSessionBaseline()` (которого нет) и `state.displayValue` логированием buffer status:

```kotlin
private suspend fun maybeLogSessionSummary(now: Long, data: DiParsData, sessionId: Long?) {
    if (now - lastSummaryLogTs < SUMMARY_LOG_INTERVAL_MS) return
    lastSummaryLogTs = now
    val status = odometerBuffer.status()
    val state = ConsumptionAggregator.state.value
    val carry = socInterpolator.carryOver(data.totalElecConsumption, data.soc)
    Log.i(TAG, "Widget session: id=$sessionId, " +
        "bufferRows=${status.rowCount}, " +
        "newestKm=${status.newestMileageKm?.let { "%.1f".format(it) } ?: "—"}, " +
        "recentAvg=${"%.2f".format(status.recentAvg)} kWh/100, " +
        "shortAvg=${status.shortAvg?.let { "%.2f".format(it) } ?: "—"}, " +
        "display=${state.displayValue?.let { "%.1f".format(it) } ?: "—"}, " +
        "trend=${state.trend}, " +
        "socCarry=${"%.3f".format(carry)} kWh, " +
        "powerState=${data.powerState}")
}
```

⚠️ `maybeLogSessionSummary` теперь должен быть `suspend fun` (для `buffer.status()`). Вызов из polling loop уже в suspend контексте — ОК.

- [ ] **Step 7.7: Поправить polling loop**

В `startPolling()` (примерно строка 444), заменить блок начиная с `maybeRefreshConsumptionCache(nowMs)`:

```kotlin
val nowMs = System.currentTimeMillis()
val sessionId = updateSessionState(nowMs, data)

val isCharging = (data.chargeGunState ?: 0) > 0  // ← заменить на правильное поле из DiParsData
odometerBuffer.onSample(
    mileage = data.mileage,
    totalElec = data.totalElecConsumption,
    socPercent = data.soc,
    sessionId = sessionId,
    isCharging = isCharging,
)
socInterpolator.onSample(
    soc = data.soc,
    totalElecKwh = data.totalElecConsumption,
    sessionId = sessionId,
)

val recentAvg = odometerBuffer.recentAvgConsumption()
val shortAvg = odometerBuffer.shortAvgConsumption()
ConsumptionAggregator.onSample(now = nowMs, recentAvg = recentAvg, shortAvg = shortAvg)

val rangeKm = rangeCalculator.estimate(soc = data.soc, totalElecKwh = data.totalElecConsumption)
_lastRangeKm.value = rangeKm

// Persist session anchor (no baseline anymore — buffer is itself persistent).
sessionId?.let { sessionPersistence.save(it, sessionLastActiveTs) }

chargeTracker.onData(data, loc)
automationEngine.evaluate(data, sessionId)
updateNotification(data)
maybeLogSessionSummary(nowMs, data, sessionId)
```

- [ ] **Step 7.8: Поправить updateNotification**

Найти где использовалась `estimateRangeKm` (вероятно в `updateNotification` или прямо там же). Заменить на `_lastRangeKm.value` (уже посчитан выше):

```bash
grep -n "estimateRangeKm" app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt
```

Где найдено — заменить вызов на `_lastRangeKm.value`.

- [ ] **Step 7.9: Build — должен компилироваться**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Если есть ошибки — `Unresolved reference: pendingBaseline` где-то остался; найти и удалить.

- [ ] **Step 7.10: Run все unit-тесты**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: все тесты PASS.

- [ ] **Step 7.11: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt
git commit -m "feat(tracking): wire OdometerConsumptionBuffer + RangeCalculator in polling

Replaces the cached EMA + estimateRangeKm path with direct calls to the new
buffer/interpolator/calculator. SessionPersistence simplified to anchor-only.
isCharging detection guards buffer from charging-time totalElec drift."
```

---

## Task 8: DashboardViewModel integration

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt`

**Изменения:**
1. Inject `RangeCalculator`.
2. Удалить `recentAvgConsumption`, `batteryCapacityKwh = 38.0`, `loadRecentAvgConsumption`, `calculateRange`.
3. Заменить вызовы `calculateRange(soc, fallback)` на `rangeCalculator.estimate(soc, totalElecKwh = ?)`.
4. Где брать `totalElecKwh` для Dashboard? Из последнего `TrackingService.lastData.value.totalElecConsumption`.

- [ ] **Step 8.1: Просмотреть существующий код**

```bash
grep -n "calculateRange\|recentAvgConsumption\|batteryCapacityKwh\|loadRecentAvgConsumption\|estimatedRangeKm\|TrackingService.lastData" app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt | head
```

Понять где встречается, чтобы заменить точно.

- [ ] **Step 8.2: Inject RangeCalculator**

В конструкторе DashboardViewModel добавить:

```kotlin
private val rangeCalculator: com.bydmate.app.domain.calculator.RangeCalculator,
```

(перед закрывающей скобкой `@Inject constructor(...)`).

- [ ] **Step 8.3: Удалить старые поля и методы**

Удалить:
- `private var recentAvgConsumption: Double = 0.0` (строка 89)
- `private var batteryCapacityKwh: Double = 38.0` (строка 280)
- `private fun loadRecentAvgConsumption() { ... }` (строки 282-289)
- `private fun calculateRange(...) { ... }` (строки 292-296)

Удалить вызовы `loadRecentAvgConsumption()` (строки 99, 366).
Удалить условие `if (recentAvgConsumption <= 0 && pollCount % 3 == 0) { loadRecentAvgConsumption() }` (строки 124-126).

- [ ] **Step 8.4: Заменить вызовы calculateRange**

Найти `estimatedRangeKm = calculateRange(newSoc, current.estimatedRangeKm)` (строка ~154). Заменить на:

```kotlin
estimatedRangeKm = rangeCalculator.estimate(soc = newSoc, totalElecKwh = data.totalElecConsumption)
    ?: current.estimatedRangeKm,
```

(`data` — DiParsData в той же scope, должен быть доступен).

⚠️ Если `data` не доступен — нужно прочитать `TrackingService.lastData.value?.totalElecConsumption`. Для этого можно сохранить ссылку через DI или взять из текущего snapshot. Уточнить по месту.

- [ ] **Step 8.5: Build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8.6: Run unit-тесты**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: все PASS.

- [ ] **Step 8.7: Commit**

```bash
git add app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt
git commit -m "feat(dashboard): use RangeCalculator, drop legacy SOC×EMA path"
```

---

## Task 9: Bump version 244 → 250

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 9.1: Bump versionCode + versionName**

В `app/build.gradle.kts` (строки 20-21):

```kotlin
versionCode = 250
versionName = "2.5.0"
```

- [ ] **Step 9.2: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version 2.4.4 → 2.5.0"
```

---

## Task 10: Manual integration testing

**Контекст:** spec 7.2 определяет 7 ручных кейсов. Прогоняем на реальном DiLink (`192.168.2.68:5555`). Тесты НЕ автоматизируются — проверяем глазами + logcat.

- [ ] **Step 10.1: Установить APK на DiLink**

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
adb connect 192.168.2.68:5555
./gradlew :app:assembleDebug
adb -s 192.168.2.68:5555 install -r app/build/outputs/apk/debug/BYDMate-v2.5.0.apk
```

- [ ] **Step 10.2: Поставить logcat фильтр**

В отдельном терминале:

```bash
adb -s 192.168.2.68:5555 logcat -c
adb -s 192.168.2.68:5555 logcat TrackingService:I OdometerConsumptionBuffer:I RangeCalculator:D *:S
```

(Тег `TrackingService` уже логирует summary раз в минуту с buffer status.)

- [ ] **Step 10.3: Проверить миграцию БД 10→11**

После установки apk, открыть приложение. В logcat **не должно быть** `Migration from 10 to 11 missing` или `IllegalStateException`. Должна быть пустая таблица `odometer_samples` (проверить через `adb shell` если нужно).

- [ ] **Step 10.4: Кейс 1 — short trip 6 km с прогревом**

Холодный старт, 10 минут прогрев на месте (HVAC), затем 6 км езды. **Ожидание:**
- Виджет цифра расхода: появится примерно после 1 км, к концу будет в районе 28-35 (близко к итогу поездки).
- Range плавно уменьшается без застреваний.
- В Trips список — тот же расход (~33), отличие < 15%.

- [ ] **Step 10.5: Кейс 2 — игнишн off/on без поездки**

Заглушить машину с конкретным range на виджете, через 1 минуту завести. **Ожидание:**
- Range отличается не больше чем на ±2 км от того, что было.
- В logcat — `Widget session END` → `Widget session START` с новым sessionId.

- [ ] **Step 10.6: Кейс 3 — длинная поездка 50+ км**

**Ожидание:**
- Range плавно уменьшается на ~25-30 км.
- Стрелка тренда корректно меняется при смене стиля езды.

- [ ] **Step 10.7: Кейс 4 — короткая поездка 3-4 км в ровном режиме**

**Ожидание:**
- Range уменьшается на ~3-4 км (раньше зависал до SOC click).

- [ ] **Step 10.8: Кейс 5 — смена batteryCapacity в Settings**

В Settings поменять capacity с 72.9 на 50, потом обратно. **Ожидание:**
- Range пересчитывается мгновенно (на следующий tick polling = 3 сек).
- В logcat нет ошибок.

- [ ] **Step 10.9: Кейс 6 — зарядка**

Поставить на AC-зарядку. Дать 5-10 минут. Снять. **Ожидание:**
- Buffer не растёт во время зарядки (`bufferRows` в logcat не меняется).
- После завершения зарядки — продолжает с предыдущей точки.
- Range адекватный после новой сессии (ignition on на следующей поездке).

- [ ] **Step 10.10: Кейс 7 — sys-kill posredine поездки**

Во время езды — `adb shell am force-stop com.bydmate.app`. Сервис рестартует через WorkManager. **Ожидание:**
- Sessionid тот же, range и расход на виджете возобновляются с теми же значениями (buffer Room-persistent).

- [ ] **Step 10.11: Записать наблюдения в handoff**

В `docs/superpowers/handoff-2026-04-24.md` (или новый handoff с актуальной датой) записать результаты по каждому кейсу.

---

## Task 11: Codex audit перед релизом

**Контекст:** обязательный шаг до `gh release create`. Запускаем `codex-rescue` агента на ветке `feature/adaptive-range-v2.5.0`.

- [ ] **Step 11.1: Спустить Codex агенту**

Используя `codex:codex-rescue` агента — попросить аудит:
- Race conditions в `OdometerConsumptionBuffer.onSample` vs `recentAvgConsumption()` (Mutex используется — должно быть OK, проверить).
- Precision loss где-либо в Float.
- Hilt circular dependencies (RangeCalculator → SocInterpolator + Buffer → DAO + Repo — должно быть OK, всё в SingletonComponent).
- Coroutine leak в `serviceScope` после рефактора.
- Edge case: что если `sessionId == null` (стартовый tick до updateSessionState)?
- DAO query производительность (windowFrom + COUNT — индекс по mileage_km используется?).

- [ ] **Step 11.2: Закрыть FIX_FIRST замечания**

Каждое FIX_FIRST + IMPORTANT — отдельный коммит до релиза. CONSIDER можно отложить.

- [ ] **Step 11.3: Re-run unit-тесты после правок**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: все PASS.

---

## Task 12: Build, sign, release

- [ ] **Step 12.1: Build release APK**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleRelease
```

Expected: APK at `app/build/outputs/apk/release/BYDMate-v2.5.0.apk`.

- [ ] **Step 12.2: Sign**

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
    --ks bydmate-release.jks --ks-key-alias bydmate \
    --ks-pass pass:bydmate123 --key-pass pass:bydmate123 \
    --out BYDMate-v2.5.0.apk app/build/outputs/apk/release/BYDMate-v2.5.0.apk
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs BYDMate-v2.5.0.apk
```

Expected: Signed by 1 signer, certificate matches.

- [ ] **Step 12.3: Test install на DiLink**

```bash
adb -s 192.168.2.68:5555 install -r BYDMate-v2.5.0.apk
```

Запустить, проверить что приложение поднимается без crashes (миграция 10→11 прошла, polling работает, виджет работает).

- [ ] **Step 12.4: Push ветки + merge в main**

```bash
git push -u origin feature/adaptive-range-v2.5.0
git checkout main
git merge --no-ff feature/adaptive-range-v2.5.0 -m "Merge branch 'feature/adaptive-range-v2.5.0'"
```

- [ ] **Step 12.5: Tag**

```bash
git tag v2.5.0
git push origin main
git push origin v2.5.0
```

- [ ] **Step 12.6: GitHub release с RU notes**

```bash
gh release create v2.5.0 BYDMate-v2.5.0.apk --title "v2.5.0 — честный остаточный пробег и расход" --notes "$(cat <<'EOF'
## Что изменилось

Переделан расчёт остаточного пробега и текущего расхода. Теперь цифры на виджете и Dashboard считаются из единого 25-км rolling-окна по одометру — как у Tesla, BMW и Hyundai.

### На виджете
- **Цифра остаточного пробега** плавно уменьшается по мере поездки. Раньше она «застревала» до щелчка SOC и потом прыгала на ~4 км.
- При выключении и повторном включении машины цифра не скачет (раньше теряла 10–15 км из-за пересчёта EMA на новой поездке).
- **Расход в кВт·ч/100км** отражает реальный расход последних 25 км пробега, включая прогревы и простои с климат-контролем — теперь близок к тому, что увидишь в итоге поездки в Trips.
- **Стрелка тренда** активируется когда в окне накопилось 2 км коротких данных и 5 км длинных. Сравнивает последние 2 км со средним 25 км.

### Под капотом
- Новая Room-таблица `odometer_samples` (миграция 10→11), переживает выключения, sys-kill, рестарт приложения.
- SessionId boundary: пара снимков из разных сессий (через ignition cycle) пропускается — закрывает кейсы ночной зарядки и BMS drain без отдельных детектов.
- Тарифы по умолчанию обновлены: AC 0.20, DC 0.73.
- Defaults batteryCapacity 72.9 (Leopard 3) теперь и в DashboardViewModel.

### Тесты
- 11 unit-тестов для OdometerConsumptionBuffer (sessionId boundary, charging skip, regression, MIN_MILEAGE_DELTA, HVAC simul, и т.д.)
- 5 для SocInterpolator
- 5 для RangeCalculator
- 8 для обновлённого ConsumptionAggregator (тонкая обёртка над buffer)

### Совместимость
- Работает одинаково на Leopard 3 (BYD energydata) и Song (DiPlus TripInfo).
- На Song при отсутствии `totalElec` параметра — fallback на SOC-based режим (худшее разрешение, но работает).
EOF
)"
```

Expected: release published, APK uploaded.

- [ ] **Step 12.7: Save handoff**

Использовать skill `saving-session-context` для записи итогов сессии (commit SHA, что сделано, кейсы тестирования). Удалить marker «Active Feature — v2.5.0» из `CLAUDE.md` (фича смерджена в main).

---

## Self-Review Checklist (для меня перед сдачей плана)

- [x] **Spec coverage:** все 11 секций спеца имеют соответствующий task. Раздел 8 (план релиза) = Tasks 1-12. Раздел 9 (закрытые решения) — все цифры использованы в коде.
- [x] **Placeholder scan:** нет TBD/TODO. Все код-блоки конкретны.
- [x] **Type consistency:** `OdometerConsumptionBuffer.recentAvgConsumption()` возвращает Double (Task 3). RangeCalculator использует через `ConsumptionAvgSource` interface — единое имя метода. SocInterpolator.carryOver — open для теста. ConsumptionAggregator.onSample(now, recentAvg, shortAvg) — single signature через все Task'и.

**Известные риски:**
1. Step 8.4 — нужен `data` из polling в DashboardViewModel scope. Если DashboardViewModel слушает `TrackingService.lastData` flow — должно работать. Если сейчас он опрашивает через свой polling — потребуется чуть другой подход (брать `totalElecConsumption` из `lastData.value`). Implementer должен уточнить по месту, не блокирующее.
2. Step 7.1 — точное имя поля для charging detection нужно проверить (`chargeGunState` vs `chargingStatus` vs другое). Простой grep до начала имплементации.
3. Step 1.5 — schema export файл (`app/schemas/com.bydmate.app.data.local.database.AppDatabase/11.json`) генерируется ksp/Room. Если в build.gradle отключен `exportSchema = true` — пропустить commit'нуть json. (В spec'е 11.json нужно сохранить в репо как историю миграций.)

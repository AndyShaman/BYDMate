# Phase 1.5 Implementation Plan — Shared Adaptive Loop + Native Trip Recording

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. All code-writing subagents MUST be dispatched with `model: "sonnet"` (per `feedback_subagent_code_model.md`).

**Goal:** Replace TrackingService's bespoke poll loop with one `SharedAdaptiveLoop` that owns the single `NativeParsReader` consumer, broadcasts a `SharedFlow<DiParsData>` to every read consumer (Dashboard, ChargingDetector, Iternio, Alice), and adds native trip recording (`TripRecorder`) that fires only on vehicles without BYD `energydata` SQLite.

**Architecture:**
- `SharedAdaptiveLoop` (new) — single coroutine, FSM cadence (drive 1 s / charge 5 s / parked 5 s / idle 30 s), `SharedFlow<DiParsData>(replay = 1, BufferOverflow.DROP_OLDEST)`, writes `last_state` snapshot every tick.
- `TripRecorder` (new) — subscribes to the flow, watches `powerState` transitions, writes `TripEntity` rows tagged `source = "native_polling"` only when `EnergyDataReader.isAvailable() == false`. On Leopard 3 it stays passive (still updates `last_state` for cold-start recovery, never writes trips).
- Surgical TrackingService swap: existing poll body (`TrackingService.kt:626-766`) keeps all downstream logic (Iternio, charging detector feed, Alice latestData, automation, overlays); only the `parsReader.fetch() + delay()` loop is replaced by `sharedAdaptiveLoop.flow.collect { data -> existingProcessing(data) }`.

**Tech Stack:**
- Kotlin, coroutines + Flow (kotlinx.coroutines.flow.SharedFlow, MutableSharedFlow, replay/extraBufferCapacity).
- Hilt for DI (singleton `SharedAdaptiveLoop`).
- Room 2.6.1 (existing `AppDatabase` v13 → v14 with one new entity `LastStateEntity`).
- JVM unit tests (JUnit4 + kotlinx-coroutines-test + Turbine) for FSM, trip recorder, cold-start, fallback.
- Robolectric `MigrationTestHelper` for migration v13→v14.

**Branch:** `feature/native-data-stack`. Builds on commit `003b5d6` (spec).

---

## File Map

### Create
- `app/src/main/kotlin/com/bydmate/app/data/loop/SharedAdaptiveLoop.kt` — single-coroutine read pump + FSM + flow + last_state writer.
- `app/src/main/kotlin/com/bydmate/app/data/loop/LoopState.kt` — `enum class LoopState { DRIVE, CHARGE, PARKED, IDLE }` + `data class CadenceConfig`.
- `app/src/main/kotlin/com/bydmate/app/data/trips/TripRecorder.kt` — passive/active trip recorder.
- `app/src/main/kotlin/com/bydmate/app/data/trips/TripSource.kt` — constants `LIVE`, `ENERGYDATA`, `NATIVE_POLLING`.
- `app/src/main/kotlin/com/bydmate/app/data/local/entity/LastStateEntity.kt` — Room entity.
- `app/src/main/kotlin/com/bydmate/app/data/local/dao/LastStateDao.kt` — DAO with `getCurrent`/`upsert`/`clearOpenTrip`.
- `app/src/test/kotlin/com/bydmate/app/data/loop/SharedAdaptiveLoopTest.kt`
- `app/src/test/kotlin/com/bydmate/app/data/loop/LoopFsmTest.kt`
- `app/src/test/kotlin/com/bydmate/app/data/trips/TripRecorderTest.kt`
- `app/src/test/kotlin/com/bydmate/app/data/trips/ColdStartReconciliationTest.kt`
- `app/src/test/kotlin/com/bydmate/app/data/trips/PowerStateFallbackTest.kt`
- `app/src/androidTest/kotlin/com/bydmate/app/data/local/Migration13To14Test.kt`
- `app/src/androidTest/assets/migration-fixtures/db-v13.db` — captured from a fresh v13 database via debug build.

### Add dependency
- `app/build.gradle.kts:142-150` — add `testImplementation("app.cash.turbine:turbine:1.0.0")` (used by `SharedAdaptiveLoopLifecycleTest`). If you prefer not to add the dep, rewrite Task 8's test to use the existing `kotlinx-coroutines-test` `Channel`/`first()` pattern instead.

### Modify
- `app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt` — bump `version = 14`, register `LastStateEntity`, add `LastStateDao`.
- `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt` — add `MIGRATION_13_14`, provide `LastStateDao`, provide `SharedAdaptiveLoop` (singleton, scoped to application).
- `app/src/main/kotlin/com/bydmate/app/data/local/EnergyDataReader.kt` — add `fun isAvailable(): Boolean`.
- `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt:626-766` — replace `startPolling()` body with `sharedAdaptiveLoop.flow.collect { data -> ... }`.
- `app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt` — drop internal `parsReader.fetch()` calls, accept `DiParsData` via constructor injection of `SharedAdaptiveLoop.flow` or via direct method calls from the service.
- `app/src/main/kotlin/com/bydmate/app/data/remote/AlicePollingManager.kt` — replace its 2.5 s polling `delay` loop with a flow collector that updates `latestData` on every tick.
- `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt` — wire `sharedAdaptiveLoop.flow` into the existing live-data flow (or via existing repository).
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt#runDiagnostics` — consume `sharedAdaptiveLoop.flow.first()` instead of an ad-hoc `parsReader.fetch()`.
- `app/src/main/kotlin/com/bydmate/app/data/local/HistoryImporter.kt` — replace literal `"energydata"` with `TripSource.ENERGYDATA`; add `source`-aware dedup.

---

## Conventions for every task

- **Branch:** stay on `feature/native-data-stack`.
- **Commits:** one commit per task with message prefix `phase-1.5(<area>):` (e.g. `phase-1.5(loop): SharedAdaptiveLoop skeleton`).
- **TDD order:** write the failing test, run it, confirm it fails, implement, run it, confirm it passes, commit.
- **Build commands:**
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  export ANDROID_HOME=$HOME/Library/Android/sdk
  ./gradlew :app:testDebugUnitTest --tests "<FQN>"
  ./gradlew :app:assembleDebug   # only when explicitly listed
  ```
- **Never build/sign/release APK** without an explicit user instruction (`собирай` / `выпускай`).

---

## Group A — Foundation (DB, entity, DAO, TripSource)

### Task 1: TripSource constants

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/trips/TripSource.kt`
- Test: `app/src/test/kotlin/com/bydmate/app/data/trips/TripSourceTest.kt`

- [ ] **Step 1 — Write the failing test**

  ```kotlin
  package com.bydmate.app.data.trips

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class TripSourceTest {
      @Test
      fun `constants match wire values used in DB`() {
          assertEquals("live", TripSource.LIVE)
          assertEquals("energydata", TripSource.ENERGYDATA)
          assertEquals("native_polling", TripSource.NATIVE_POLLING)
      }

      @Test
      fun `all returns full set in stable order`() {
          assertEquals(listOf("live", "energydata", "native_polling"), TripSource.all)
      }
  }
  ```

- [ ] **Step 2 — Run to confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.trips.TripSourceTest"
  ```
  Expected: compilation failure (`Unresolved reference: TripSource`).

- [ ] **Step 3 — Implement**

  ```kotlin
  package com.bydmate.app.data.trips

  /**
   * Stable wire values for [TripEntity.source]. Magic strings replaced here.
   */
  object TripSource {
      const val LIVE = "live"
      const val ENERGYDATA = "energydata"
      const val NATIVE_POLLING = "native_polling"
      val all = listOf(LIVE, ENERGYDATA, NATIVE_POLLING)
  }
  ```

- [ ] **Step 4 — Run to confirm pass**

  Same command as Step 2. Expected: 2/2 pass.

- [ ] **Step 5 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/trips/TripSource.kt \
          app/src/test/kotlin/com/bydmate/app/data/trips/TripSourceTest.kt
  git commit -m "phase-1.5(trips): TripSource constants for trip provenance"
  ```

---

### Task 2: `LastStateEntity` Room entity

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/local/entity/LastStateEntity.kt`

- [ ] **Step 1 — Implement entity (no test needed; covered by DAO test in Task 3)**

  ```kotlin
  package com.bydmate.app.data.local.entity

  import androidx.room.ColumnInfo
  import androidx.room.Entity
  import androidx.room.PrimaryKey

  /**
   * Single-row table (id always 1) that persists the last-seen vehicle snapshot.
   * Used by SharedAdaptiveLoop to recover the open trip after a cold start.
   *
   * Distinct from in-memory LastSessionRepository, which keeps SOC bookmarks
   * for HistoryImporter SOC enrichment within a single session.
   */
  @Entity(tableName = "last_state")
  data class LastStateEntity(
      @PrimaryKey val id: Int = 1,
      @ColumnInfo(name = "ts") val ts: Long,
      @ColumnInfo(name = "soc") val soc: Int? = null,
      @ColumnInfo(name = "mileage") val mileage: Double? = null,
      @ColumnInfo(name = "ignition") val ignition: Int? = null,
      @ColumnInfo(name = "open_trip_id") val openTripId: Long? = null,
      @ColumnInfo(name = "trip_start_ts") val tripStartTs: Long? = null,
      @ColumnInfo(name = "trip_start_soc") val tripStartSoc: Int? = null,
      @ColumnInfo(name = "trip_start_mileage") val tripStartMileage: Double? = null,
      @ColumnInfo(name = "energydata_available") val energydataAvailable: Int = 0
  )
  ```

- [ ] **Step 2 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/local/entity/LastStateEntity.kt
  git commit -m "phase-1.5(db): LastStateEntity for cold-start recovery"
  ```

---

### Task 3: `LastStateDao` + Robolectric DAO test

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/local/dao/LastStateDao.kt`
- Test: `app/src/test/kotlin/com/bydmate/app/data/local/dao/LastStateDaoTest.kt`

- [ ] **Step 1 — Write the failing test (Robolectric in-memory Room)**

  ```kotlin
  package com.bydmate.app.data.local.dao

  import android.content.Context
  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import com.bydmate.app.data.local.database.AppDatabase
  import com.bydmate.app.data.local.entity.LastStateEntity
  import kotlinx.coroutines.runBlocking
  import org.junit.After
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Before
  import org.junit.Test
  import org.junit.runner.RunWith
  import org.robolectric.RobolectricTestRunner

  @RunWith(RobolectricTestRunner::class)
  class LastStateDaoTest {

      private lateinit var db: AppDatabase
      private lateinit var dao: LastStateDao

      @Before fun setUp() {
          val ctx = ApplicationProvider.getApplicationContext<Context>()
          db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
              .allowMainThreadQueries()
              .build()
          dao = db.lastStateDao()
      }

      @After fun tearDown() = db.close()

      @Test fun `empty table returns null`() = runBlocking {
          assertNull(dao.getCurrent())
      }

      @Test fun `upsert replaces row with same id`() = runBlocking {
          dao.upsert(LastStateEntity(id = 1, ts = 1000L, soc = 80))
          dao.upsert(LastStateEntity(id = 1, ts = 2000L, soc = 75, openTripId = 42L))
          val row = dao.getCurrent()
          assertEquals(2000L, row?.ts)
          assertEquals(75, row?.soc)
          assertEquals(42L, row?.openTripId)
      }

      @Test fun `clearOpenTrip nulls the trip fields only`() = runBlocking {
          dao.upsert(
              LastStateEntity(
                  id = 1, ts = 2000L, soc = 75,
                  openTripId = 42L, tripStartTs = 1000L,
                  tripStartSoc = 80, tripStartMileage = 12345.6
              )
          )
          dao.clearOpenTrip()
          val row = dao.getCurrent()!!
          assertNull(row.openTripId)
          assertNull(row.tripStartTs)
          assertNull(row.tripStartSoc)
          assertNull(row.tripStartMileage)
          assertEquals(75, row.soc) // unrelated fields untouched
      }
  }
  ```

- [ ] **Step 2 — Run to confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.local.dao.LastStateDaoTest"
  ```
  Expected: compilation failure (`AppDatabase.lastStateDao` missing).

- [ ] **Step 3 — Implement DAO**

  ```kotlin
  package com.bydmate.app.data.local.dao

  import androidx.room.Dao
  import androidx.room.Insert
  import androidx.room.OnConflictStrategy
  import androidx.room.Query
  import com.bydmate.app.data.local.entity.LastStateEntity

  @Dao
  interface LastStateDao {
      @Query("SELECT * FROM last_state WHERE id = 1")
      suspend fun getCurrent(): LastStateEntity?

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      suspend fun upsert(state: LastStateEntity)

      /**
       * Mark a new open trip in last_state. Writes to row id=1 (creating it if absent
       * via UPSERT semantics on the caller's side — most callers will read getCurrent()
       * first and merge). Called by TripRecorder on trip open.
       */
      @Query(
          """
          UPDATE last_state
          SET open_trip_id = COALESCE(open_trip_id, :startTs),
              trip_start_ts = :startTs,
              trip_start_soc = :startSoc,
              trip_start_mileage = :startMileage,
              ts = :now
          WHERE id = 1
          """
      )
      suspend fun openTrip(startTs: Long, startSoc: Int?, startMileage: Double?, now: Long): Int

      @Query(
          """
          UPDATE last_state
          SET open_trip_id = NULL,
              trip_start_ts = NULL,
              trip_start_soc = NULL,
              trip_start_mileage = NULL
          WHERE id = 1
          """
      )
      suspend fun clearOpenTrip()
  }
  ```

  > **Note on UPSERT semantics:** `@Query UPDATE` returns the count of rows affected. `TripRecorder.openTrip(...)` must handle the case where last_state has no row yet (very first ever tick before the loop wrote a snapshot). In that case `openTrip()` returns 0, and the caller falls back to `upsert(LastStateEntity(...))`. The shared loop's `persistSnapshot` (Task 8) typically writes row id=1 before TripRecorder fires, so this path is rare — but cover it.

- [ ] **Step 4 — Wire DAO + entity into AppDatabase (still v13 for now; bump happens in Task 4)**

  Locate `AppDatabase.kt`. Add `LastStateEntity::class` to the `@Database(entities = [...])` list and `abstract fun lastStateDao(): LastStateDao`. Leave `version = 13` for this step — the test uses `inMemoryDatabaseBuilder` which does not run migrations, so the new entity is created on the fly without bumping the version yet.

  > Reviewer-self-check: the project version bump and migration come in the next task; do not change `version` here.

- [ ] **Step 5 — Run to confirm pass**

  Same command as Step 2. Expected: 3/3 pass.

- [ ] **Step 6 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/local/dao/LastStateDao.kt \
          app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt \
          app/src/test/kotlin/com/bydmate/app/data/local/dao/LastStateDaoTest.kt
  git commit -m "phase-1.5(db): LastStateDao + AppDatabase wiring (still v13)"
  ```

---

### Task 4: Room migration v13 → v14 (additive)

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt` — bump `version = 14`.
- Modify: `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt` — add `private val MIGRATION_13_14` (private object inside `AppModule`) and chain it in `.addMigrations(...)`.
- Modify: `app/src/test/kotlin/com/bydmate/app/di/AppModuleMigrationsForTest.kt` — re-export `MIGRATION_13_14` (mirror SQL exactly).
- Test: `app/src/androidTest/kotlin/com/bydmate/app/data/local/Migration13To14Test.kt`
- Test fixture: `app/src/androidTest/assets/migration-fixtures/db-v13.db`

> **Correction (codex):** `MigrationTestHelper.createDatabase(name, 13)` only builds a synthetic schema from the auto-generated schema JSON for v13 — it does NOT exercise the existing Leopard 3 dataset against the migration. Spec §migration-tests requires a **real captured v13 DB**. Capture the fixture in Step 1 and load it via `helper.createDatabase(DB_NAME, 13)` after copying the asset into the helper's working directory.
>
> Codebase convention: production migrations are `private val MIGRATION_x_y` inside the `AppModule` object (see `AppModule.kt:228` for `MIGRATION_12_13`). Test access is via the existing `AppModuleMigrationsForTest` object (`app/src/test/kotlin/com/bydmate/app/di/AppModuleMigrationsForTest.kt:15-31`) that re-exports a duplicate of the SQL. Mirror that pattern — DO NOT make the production constant public.

- [ ] **Step 1 — Capture a real v13 DB fixture from a device on `main`**

  Required: a phone or DiLink with the current released build (`v2.8.x`, schema v13). On the operator's command line:

  ```bash
  # On the device:
  adb -s 192.168.2.68:5555 exec-out run-as com.bydmate.app \
      cat databases/bydmate.db > /tmp/db-v13.db

  # On the developer host (worktree root):
  mkdir -p app/src/androidTest/assets/migration-fixtures
  mv /tmp/db-v13.db app/src/androidTest/assets/migration-fixtures/db-v13.db
  ```

  Quick sanity check: `sqlite3 app/src/androidTest/assets/migration-fixtures/db-v13.db "PRAGMA user_version;"` must print `13`.

  > If no v13 device is reachable, dispatch operator-in-car task: ask Andy to pull the DB from his DiLink. Do **not** fall back to a synthetic `createDatabase(13)` — that defeats the purpose of the migration test (spec §migration-tests).

- [ ] **Step 2 — Add the test-side migration mirror**

  Edit `app/src/test/kotlin/com/bydmate/app/di/AppModuleMigrationsForTest.kt`. Append inside the object:

  ```kotlin
  val MIGRATION_13_14 = object : Migration(13, 14) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
              """
              CREATE TABLE IF NOT EXISTS last_state (
                  id INTEGER NOT NULL PRIMARY KEY,
                  ts INTEGER NOT NULL,
                  soc INTEGER,
                  mileage REAL,
                  ignition INTEGER,
                  open_trip_id INTEGER,
                  trip_start_ts INTEGER,
                  trip_start_soc INTEGER,
                  trip_start_mileage REAL,
                  energydata_available INTEGER NOT NULL DEFAULT 0
              )
              """.trimIndent()
          )
      }
  }
  ```

  Update the file's header comment to mention `MIGRATION_13_14` alongside the existing `MIGRATION_11_12` / `MIGRATION_12_13`.

- [ ] **Step 3 — Write the failing migration test**

  ```kotlin
  package com.bydmate.app.data.local

  import androidx.room.testing.MigrationTestHelper
  import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
  import androidx.test.ext.junit.runners.AndroidJUnit4
  import androidx.test.platform.app.InstrumentationRegistry
  import com.bydmate.app.data.local.database.AppDatabase
  import com.bydmate.app.di.AppModuleMigrationsForTest
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Rule
  import org.junit.Test
  import org.junit.runner.RunWith
  import java.io.File

  @RunWith(AndroidJUnit4::class)
  class Migration13To14Test {

      private val DB_NAME = "migration-test.db"

      @get:Rule
      val helper = MigrationTestHelper(
          InstrumentationRegistry.getInstrumentation(),
          AppDatabase::class.java,
          emptyList(),
          FrameworkSQLiteOpenHelperFactory()
      )

      private fun copyFixtureIntoHelperPath() {
          // Helper opens DB at context.getDatabasePath(DB_NAME). We copy the captured
          // v13 asset there so `createDatabase` sees a real schema+rows, not a synth.
          val ctx = InstrumentationRegistry.getInstrumentation().targetContext
          val target = ctx.getDatabasePath(DB_NAME)
          target.parentFile?.mkdirs()
          ctx.assets.open("migration-fixtures/db-v13.db").use { input ->
              target.outputStream().use { out -> input.copyTo(out) }
          }
      }

      @Test fun migrate_13_to_14_preserves_real_data_and_adds_last_state() {
          copyFixtureIntoHelperPath()
          val v13 = helper.createDatabase(DB_NAME, 13)
          val tripsBefore = v13.query("SELECT count(*) FROM trips").use { c ->
              c.moveToFirst(); c.getInt(0)
          }
          assertTrue("fixture must contain at least one trip", tripsBefore > 0)
          v13.close()

          val v14 = helper.runMigrationsAndValidate(
              DB_NAME, 14, true, AppModuleMigrationsForTest.MIGRATION_13_14
          )

          // All trip rows preserved.
          v14.query("SELECT count(*) FROM trips").use { c ->
              c.moveToFirst()
              assertEquals(tripsBefore, c.getInt(0))
          }
          // last_state table exists and is empty.
          v14.query("SELECT count(*) FROM last_state").use { c ->
              c.moveToFirst()
              assertEquals(0, c.getInt(0))
          }
          v14.close()
      }
  }
  ```

- [ ] **Step 4 — Confirm failure**

  ```bash
  ./gradlew :app:connectedDebugAndroidTest \
      --tests "com.bydmate.app.data.local.Migration13To14Test"
  ```
  Expected: unresolved `MIGRATION_13_14` in `AppModule`, or schema mismatch on `last_state` if the entity is in `AppDatabase` but no production migration is registered.

- [ ] **Step 5 — Implement the production migration**

  In `AppModule.kt`, mirror `MIGRATION_12_13` (line 228) — add as a `private val` inside the `AppModule` object **immediately after** `MIGRATION_12_13`:

  ```kotlin
  private val MIGRATION_13_14 = object : Migration(13, 14) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
              """
              CREATE TABLE IF NOT EXISTS last_state (
                  id INTEGER NOT NULL PRIMARY KEY,
                  ts INTEGER NOT NULL,
                  soc INTEGER,
                  mileage REAL,
                  ignition INTEGER,
                  open_trip_id INTEGER,
                  trip_start_ts INTEGER,
                  trip_start_soc INTEGER,
                  trip_start_mileage REAL,
                  energydata_available INTEGER NOT NULL DEFAULT 0
              )
              """.trimIndent()
          )
      }
  }
  ```

  Append `MIGRATION_13_14` to the existing `.addMigrations(...)` call at `AppModule.kt:246` (right after `MIGRATION_12_13`).

  In `AppDatabase.kt:42`, change `version = 13` → `version = 14`.

- [ ] **Step 6 — Run to confirm pass**

  Same command as Step 4. Expected: 1/1 pass.

- [ ] **Step 7 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/local/database/AppDatabase.kt \
          app/src/main/kotlin/com/bydmate/app/di/AppModule.kt \
          app/src/test/kotlin/com/bydmate/app/di/AppModuleMigrationsForTest.kt \
          app/src/androidTest/kotlin/com/bydmate/app/data/local/Migration13To14Test.kt \
          app/src/androidTest/assets/migration-fixtures/db-v13.db
  git commit -m "phase-1.5(db): migration v13->v14 adds last_state table (real-fixture test)"
  ```

---

### Task 5: `EnergyDataReader.isAvailable()`

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/local/EnergyDataReader.kt`
- Test: `app/src/test/kotlin/com/bydmate/app/data/local/EnergyDataReaderAvailabilityTest.kt`

> **Correction (codex):** `/storage/emulated/0/energydata` is a **directory**, not a file (`EnergyDataReader.kt:27-28`). Reuse existing helpers `findDbViaListFiles(dir)` and `findDbViaKnownNames(dir)` (`EnergyDataReader.kt:287-298`) — they handle Android 11+ `listFiles() == null` and fall back to the known `EC_database.db` / `energydata.db` / `energy.db` filenames.

- [ ] **Step 1 — Write the failing test (Robolectric)**

  ```kotlin
  package com.bydmate.app.data.local

  import android.content.Context
  import androidx.test.core.app.ApplicationProvider
  import io.mockk.every
  import io.mockk.spyk
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Rule
  import org.junit.Test
  import org.junit.rules.TemporaryFolder
  import org.junit.runner.RunWith
  import org.robolectric.RobolectricTestRunner
  import java.io.File

  @RunWith(RobolectricTestRunner::class)
  class EnergyDataReaderAvailabilityTest {
      @get:Rule val tmp = TemporaryFolder()

      private fun readerWithDir(dir: File): EnergyDataReader {
          val ctx = ApplicationProvider.getApplicationContext<Context>()
          val spy = spyk(EnergyDataReader(ctx))
          every { spy.energyDataDir() } returns dir
          return spy
      }

      @Test fun `missing directory returns false`() {
          assertFalse(readerWithDir(File(tmp.root, "absent")).isAvailable())
      }

      @Test fun `empty directory returns false`() {
          val dir = tmp.newFolder("energydata")
          assertFalse(readerWithDir(dir).isAvailable())
      }

      @Test fun `directory with EC_database_db returns true`() {
          val dir = tmp.newFolder("energydata")
          File(dir, "EC_database.db").writeBytes(
              "SQLite format 3 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(100)
          )
          assertTrue(readerWithDir(dir).isAvailable())
      }

      @Test fun `directory with arbitrary db file (listFiles path) returns true`() {
          val dir = tmp.newFolder("energydata")
          File(dir, "random_name.db").writeBytes(
              "SQLite format 3 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(100)
          )
          assertTrue(readerWithDir(dir).isAvailable())
      }
  }
  ```

- [ ] **Step 2 — Run to confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.local.EnergyDataReaderAvailabilityTest"
  ```
  Expected: `Unresolved reference: isAvailable` / `energyDataDir`.

- [ ] **Step 3 — Implement**

  Add to `EnergyDataReader.kt`:

  ```kotlin
  /** Hook for tests to swap the dir. Default points at the real ENERGY_DIR_PATH. */
  internal open fun energyDataDir(): File = File(ENERGY_DIR_PATH)

  /**
   * Phase 1.5 mode switch: does the BYD energydata directory hold a readable trips DB?
   * Reuses existing find helpers — no new file probing logic.
   */
  fun isAvailable(): Boolean {
      val dir = energyDataDir()
      if (!dir.exists() || !dir.isDirectory) return false
      val db = findDbViaListFiles(dir) ?: findDbViaKnownNames(dir) ?: return false
      return db.canRead() && db.length() >= 16
  }
  ```

  > `findDbViaListFiles` / `findDbViaKnownNames` already exist at `EnergyDataReader.kt:287-298`. Reuse — do not reimplement.

- [ ] **Step 4 — Confirm pass**

  Same command as Step 2. Expected: 4/4 pass.

- [ ] **Step 5 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/local/EnergyDataReader.kt \
          app/src/test/kotlin/com/bydmate/app/data/local/EnergyDataReaderAvailabilityTest.kt
  git commit -m "phase-1.5(reader): EnergyDataReader.isAvailable via existing find helpers"
  ```

---

## Group B — SharedAdaptiveLoop

### Task 6: `LoopState` enum + `CadenceConfig`

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/loop/LoopState.kt`
- Test: `app/src/test/kotlin/com/bydmate/app/data/loop/CadenceConfigTest.kt`

- [ ] **Step 1 — Test**

  ```kotlin
  package com.bydmate.app.data.loop

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class CadenceConfigTest {
      @Test fun `default cadences match spec`() {
          val c = CadenceConfig.default()
          assertEquals(1_000L, c.intervalFor(LoopState.DRIVE))
          assertEquals(5_000L, c.intervalFor(LoopState.CHARGE))
          assertEquals(5_000L, c.intervalFor(LoopState.PARKED))
          assertEquals(30_000L, c.intervalFor(LoopState.IDLE))
      }

      @Test fun `max poll interval cap is 60s`() {
          assertEquals(60_000L, CadenceConfig.MAX_POLL_INTERVAL_MS)
      }
  }
  ```

- [ ] **Step 2 — Confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.loop.CadenceConfigTest"
  ```

- [ ] **Step 3 — Implement**

  ```kotlin
  package com.bydmate.app.data.loop

  enum class LoopState { DRIVE, CHARGE, PARKED, IDLE }

  data class CadenceConfig(
      val driveMs: Long,
      val chargeMs: Long,
      val parkedMs: Long,
      val idleMs: Long,
  ) {
      fun intervalFor(state: LoopState): Long = when (state) {
          LoopState.DRIVE -> driveMs
          LoopState.CHARGE -> chargeMs
          LoopState.PARKED -> parkedMs
          LoopState.IDLE -> idleMs
      }

      companion object {
          const val MAX_POLL_INTERVAL_MS = 60_000L
          fun default() = CadenceConfig(
              driveMs = 1_000L,
              chargeMs = 5_000L,
              parkedMs = 5_000L,
              idleMs = 30_000L,
          )
      }
  }
  ```

- [ ] **Step 4 — Pass + commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/loop/LoopState.kt \
          app/src/test/kotlin/com/bydmate/app/data/loop/CadenceConfigTest.kt
  git commit -m "phase-1.5(loop): LoopState enum + CadenceConfig"
  ```

---

### Task 7: FSM transition pure function

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/loop/LoopState.kt` — add `LoopFsm.classify(...)`.
- Test: `app/src/test/kotlin/com/bydmate/app/data/loop/LoopFsmTest.kt`

`LoopFsm.classify` is the *pure* mapping from a `DiParsData` snapshot to a `LoopState`. Pure = no IO, no time, deterministic; this is what we exercise in unit tests, and what the loop calls on every tick.

- [ ] **Step 1 — Add `DiParsDataFixtures.kt` test helper FIRST** (shared across all loop/trip tests)

  `DiParsData` has 49 required nullable fields and no default args (`DiParsData.kt:3-50`). All tests need a builder.

  Create: `app/src/test/kotlin/com/bydmate/app/data/remote/DiParsDataFixtures.kt`

  ```kotlin
  package com.bydmate.app.data.remote

  /** Test fixture: build a DiParsData with all fields null by default. Override per test. */
  fun diParsData(
      soc: Int? = null,
      speed: Int? = null,
      mileage: Double? = null,
      power: Double? = null,
      chargeGunState: Int? = null,
      maxBatTemp: Int? = null,
      avgBatTemp: Int? = null,
      minBatTemp: Int? = null,
      chargingStatus: Int? = null,
      batteryCapacityKwh: Double? = null,
      totalElecConsumption: Double? = null,
      voltage12v: Double? = null,
      maxCellVoltage: Double? = null,
      minCellVoltage: Double? = null,
      exteriorTemp: Int? = null,
      gear: Int? = null,
      powerState: Int? = null,
      insideTemp: Int? = null,
      acStatus: Int? = null,
      acTemp: Int? = null,
      fanLevel: Int? = null,
      acCirc: Int? = null,
      doorFL: Int? = null,
      doorFR: Int? = null,
      doorRL: Int? = null,
      doorRR: Int? = null,
      windowFL: Int? = null,
      windowFR: Int? = null,
      windowRL: Int? = null,
      windowRR: Int? = null,
      sunroof: Int? = null,
      trunk: Int? = null,
      hood: Int? = null,
      seatbeltFL: Int? = null,
      lockFL: Int? = null,
      tirePressFL: Int? = null,
      tirePressFR: Int? = null,
      tirePressRL: Int? = null,
      tirePressRR: Int? = null,
      driveMode: Int? = null,
      workMode: Int? = null,
      autoPark: Int? = null,
      rain: Int? = null,
      lightLow: Int? = null,
      drl: Int? = null,
  ) = DiParsData(
      soc = soc, speed = speed, mileage = mileage, power = power,
      chargeGunState = chargeGunState, maxBatTemp = maxBatTemp, avgBatTemp = avgBatTemp,
      minBatTemp = minBatTemp, chargingStatus = chargingStatus,
      batteryCapacityKwh = batteryCapacityKwh, totalElecConsumption = totalElecConsumption,
      voltage12v = voltage12v, maxCellVoltage = maxCellVoltage, minCellVoltage = minCellVoltage,
      exteriorTemp = exteriorTemp, gear = gear, powerState = powerState,
      insideTemp = insideTemp, acStatus = acStatus, acTemp = acTemp, fanLevel = fanLevel,
      acCirc = acCirc, doorFL = doorFL, doorFR = doorFR, doorRL = doorRL, doorRR = doorRR,
      windowFL = windowFL, windowFR = windowFR, windowRL = windowRL, windowRR = windowRR,
      sunroof = sunroof, trunk = trunk, hood = hood, seatbeltFL = seatbeltFL,
      lockFL = lockFL, tirePressFL = tirePressFL, tirePressFR = tirePressFR,
      tirePressRL = tirePressRL, tirePressRR = tirePressRR, driveMode = driveMode,
      workMode = workMode, autoPark = autoPark, rain = rain, lightLow = lightLow, drl = drl,
  )
  ```

- [ ] **Step 2 — Write the LoopFsm test using real semantics**

  ```kotlin
  package com.bydmate.app.data.loop

  import com.bydmate.app.data.remote.diParsData
  import org.junit.Assert.assertEquals
  import org.junit.Test

  // Per DiParsData.kt:21 — powerState: 0=OFF, 1=ON (ignition active, parked), 2=DRIVE.
  // Per DiParsData.kt:20 — gear: 1=P, 2=R, 3=N, 4=D.
  // Per DiParsData.kt:5  — speed: Int? in km/h.
  // chargeGunState != 0 == connected.

  class LoopFsmTest {

      @Test fun `charge gun connected always wins`() {
          assertEquals(LoopState.CHARGE, LoopFsm.classify(diParsData(chargeGunState = 1, powerState = 2)))
          assertEquals(LoopState.CHARGE, LoopFsm.classify(diParsData(chargeGunState = 1, powerState = 0)))
      }

      @Test fun `powerState DRIVE with speed = drive cadence`() {
          assertEquals(LoopState.DRIVE, LoopFsm.classify(diParsData(powerState = 2, speed = 25)))
      }

      @Test fun `powerState ON without movement = parked`() {
          assertEquals(LoopState.PARKED, LoopFsm.classify(diParsData(powerState = 1, speed = 0)))
          assertEquals(LoopState.PARKED, LoopFsm.classify(diParsData(powerState = 1, speed = null)))
      }

      @Test fun `powerState DRIVE without speed = parked`() {
          // DRIVE with speed=0 (foot on brake, ready) reads as parked cadence — battery
          // not draining like real motion.
          assertEquals(LoopState.PARKED, LoopFsm.classify(diParsData(powerState = 2, speed = 0)))
      }

      @Test fun `powerState OFF = idle`() {
          assertEquals(LoopState.IDLE, LoopFsm.classify(diParsData(powerState = 0)))
      }

      @Test fun `power null falls back to gear D + speed = drive`() {
          assertEquals(LoopState.DRIVE, LoopFsm.classify(diParsData(powerState = null, gear = 4, speed = 5)))
      }

      @Test fun `power null gear P = idle`() {
          assertEquals(LoopState.IDLE, LoopFsm.classify(diParsData(powerState = null, gear = 1)))
      }

      @Test fun `everything null = idle`() {
          assertEquals(LoopState.IDLE, LoopFsm.classify(diParsData()))
      }
  }
  ```

- [ ] **Step 2 — Confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.loop.LoopFsmTest"
  ```

- [ ] **Step 3 — Implement**

  In the same `LoopState.kt`:

  ```kotlin
  object LoopFsm {
      // powerState (DiParsData.kt:21): 0=OFF, 1=ON, 2=DRIVE. gear (DiParsData.kt:20): 1=P, 4=D.
      // speed (DiParsData.kt:5): Int? km/h.
      fun classify(data: com.bydmate.app.data.remote.DiParsData): LoopState {
          if (data.chargeGunState != null && data.chargeGunState != 0) return LoopState.CHARGE
          val ignitionActive = when {
              data.powerState != null -> data.powerState in 1..2
              else -> data.gear == 4 || ((data.speed ?: 0) > 0)
          }
          if (!ignitionActive) return LoopState.IDLE
          val moving = (data.speed ?: 0) > 0
          return if (moving) LoopState.DRIVE else LoopState.PARKED
      }
  }
  ```

- [ ] **Step 4 — Pass + commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/loop/LoopState.kt \
          app/src/test/kotlin/com/bydmate/app/data/loop/LoopFsmTest.kt
  git commit -m "phase-1.5(loop): LoopFsm.classify with powerState + gear/speed fallback"
  ```

---

### Task 8: `SharedAdaptiveLoop` skeleton (flow + start/stop, no FSM body yet)

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/loop/SharedAdaptiveLoop.kt`
- Test: `app/src/test/kotlin/com/bydmate/app/data/loop/SharedAdaptiveLoopLifecycleTest.kt`

- [ ] **Step 1 — Test**

  Reminder: powerState `1` = ACC (FSM idle cadence, 30s). `DiParsData` has 49 nullable required fields, so use the `diParsData(...)` builder from Task 7.

  ```kotlin
  package com.bydmate.app.data.loop

  import app.cash.turbine.test
  import com.bydmate.app.data.local.EnergyDataReader
  import com.bydmate.app.data.local.dao.LastStateDao
  import com.bydmate.app.data.nativestack.ParsReader
  import io.mockk.coEvery
  import io.mockk.every
  import io.mockk.mockk
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.test.StandardTestDispatcher
  import kotlinx.coroutines.test.TestScope
  import kotlinx.coroutines.test.advanceTimeBy
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Test

  @OptIn(ExperimentalCoroutinesApi::class)
  class SharedAdaptiveLoopLifecycleTest {

      @Test fun `emits ticks from parsReader on the schedule`() = runTest {
          val dispatcher = StandardTestDispatcher(testScheduler)
          val reader = mockk<ParsReader>()
          var n = 0
          coEvery { reader.fetch() } answers {
              n++
              // powerState=1 (ACC) → IDLE cadence (30s)
              diParsData(soc = 80 - n, mileage = 100.0 + n, voltage12v = 12.5, powerState = 1)
          }
          val lastState = mockk<LastStateDao>(relaxed = true)
          val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
          val loop = SharedAdaptiveLoop(reader, lastState, energy, dispatcher)

          val job = loop.start(TestScope(dispatcher))
          try {
              loop.flow.test {
                  advanceTimeBy(1)             // first fetch runs immediately
                  val t1 = awaitItem()
                  assertEquals(79, t1.soc)
                  advanceTimeBy(30_000)        // idle cadence
                  val t2 = awaitItem()
                  assertEquals(78, t2.soc)
              }
          } finally {
              job.cancel()
          }
      }

      @Test fun `start is idempotent`() = runTest {
          val dispatcher = StandardTestDispatcher(testScheduler)
          val reader = mockk<ParsReader>(relaxed = true)
          coEvery { reader.fetch() } returns null
          val lastState = mockk<LastStateDao>(relaxed = true)
          val energy = mockk<EnergyDataReader>(relaxed = true)
          val loop = SharedAdaptiveLoop(reader, lastState, energy, dispatcher)
          val scope = TestScope(dispatcher)
          val j1 = loop.start(scope)
          val j2 = loop.start(scope)
          assertEquals(j1, j2)
          j1.cancel()
      }
  }
  ```

- [ ] **Step 2 — Confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.loop.SharedAdaptiveLoopLifecycleTest"
  ```

- [ ] **Step 3 — Implement skeleton**

  ```kotlin
  package com.bydmate.app.data.loop

  import com.bydmate.app.data.local.EnergyDataReader
  import com.bydmate.app.data.local.dao.LastStateDao
  import com.bydmate.app.data.local.entity.LastStateEntity
  import com.bydmate.app.data.nativestack.ParsReader
  import com.bydmate.app.data.remote.DiParsData
  import kotlinx.coroutines.CoroutineDispatcher
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.channels.BufferOverflow
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.MutableSharedFlow
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.SharedFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asSharedFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.launch
  import javax.inject.Inject
  import javax.inject.Singleton

  /**
   * Single owner of NativeParsReader. All read consumers subscribe to [flow].
   * Cadence is decided per tick via [LoopFsm.classify]; backoff applies when
   * fetch() returns null.
   */
  @Singleton
  class SharedAdaptiveLoop @Inject constructor(
      private val parsReader: ParsReader,
      private val lastStateDao: LastStateDao,
      private val energyDataReader: EnergyDataReader,
      private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
      private val cadence: CadenceConfig = CadenceConfig.default(),
  ) {
      private val _flow = MutableSharedFlow<DiParsData>(
          replay = 1, extraBufferCapacity = 0,
          onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )
      val flow: SharedFlow<DiParsData> = _flow.asSharedFlow()

      private val _connected = MutableStateFlow(false)
      val connected: StateFlow<Boolean> = _connected.asStateFlow()

      private var job: Job? = null

      @Synchronized
      fun start(scope: CoroutineScope): Job {
          job?.takeIf { it.isActive }?.let { return it }
          job = scope.launch(dispatcher) { runLoop() }
          return job!!
      }

      fun stop() { job?.cancel(); job = null }

      private suspend fun runLoop() {
          var consecutiveNull = 0
          while (true) {
              val data = runCatching { parsReader.fetch() }.getOrNull()
              if (data == null) {
                  consecutiveNull++
                  _connected.value = false
                  val backoff = (cadence.intervalFor(LoopState.IDLE) * pow15(consecutiveNull))
                      .coerceAtMost(CadenceConfig.MAX_POLL_INTERVAL_MS)
                  delay(backoff)
                  continue
              }
              consecutiveNull = 0
              _connected.value = true
              _flow.emit(data)
              persistSnapshot(data)
              delay(cadence.intervalFor(LoopFsm.classify(data)))
          }
      }

      private suspend fun persistSnapshot(data: DiParsData) {
          val now = System.currentTimeMillis()
          val prev = lastStateDao.getCurrent()
          val ignition = data.powerState
          lastStateDao.upsert(
              LastStateEntity(
                  id = 1,
                  ts = now,
                  soc = data.soc,
                  mileage = data.mileage,
                  ignition = ignition,
                  openTripId = prev?.openTripId,
                  tripStartTs = prev?.tripStartTs,
                  tripStartSoc = prev?.tripStartSoc,
                  tripStartMileage = prev?.tripStartMileage,
                  energydataAvailable = if (energyDataReader.isAvailable()) 1 else 0,
              )
          )
      }

      private fun pow15(n: Int): Long {
          var v = 1.0
          repeat(n) { v *= 1.5 }
          return v.toLong().coerceAtLeast(1L)
      }
  }
  ```

- [ ] **Step 4 — Pass + commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/loop/SharedAdaptiveLoop.kt \
          app/src/test/kotlin/com/bydmate/app/data/loop/SharedAdaptiveLoopLifecycleTest.kt
  git commit -m "phase-1.5(loop): SharedAdaptiveLoop with FSM cadence + null backoff + last_state writer"
  ```

---

### Task 9: SharedAdaptiveLoop concurrency — slow subscriber must not block the loop

**Files:**
- Test: `app/src/test/kotlin/com/bydmate/app/data/loop/SharedFlowConcurrencyTest.kt`

- [ ] **Step 1 — Test**

  Spec §134 ("concurrency contract") requires: ≥5 concurrent subscribers, slow subscriber must not block the loop, late subscriber receives the latest value (replay=1). `speed` is `Int?`; use the `diParsData(...)` builder.

  ```kotlin
  package com.bydmate.app.data.loop

  import com.bydmate.app.data.local.EnergyDataReader
  import com.bydmate.app.data.local.dao.LastStateDao
  import com.bydmate.app.data.nativestack.ParsReader
  import io.mockk.coEvery
  import io.mockk.every
  import io.mockk.mockk
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.test.StandardTestDispatcher
  import kotlinx.coroutines.test.TestScope
  import kotlinx.coroutines.test.advanceTimeBy
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Test

  @OptIn(ExperimentalCoroutinesApi::class)
  class SharedFlowConcurrencyTest {

      private fun buildLoop(dispatcher: StandardTestDispatcher): Pair<SharedAdaptiveLoop, () -> Int> {
          val reader = mockk<ParsReader>()
          var counter = 0
          coEvery { reader.fetch() } answers {
              counter++
              // powerState=2 (DRIVE) + speed>0 → DRIVE cadence (1s)
              diParsData(soc = counter, voltage12v = 12.5, mileage = 1.0, powerState = 2, speed = 30)
          }
          val energy = mockk<EnergyDataReader> { every { isAvailable() } returns false }
          val lastState = mockk<LastStateDao>(relaxed = true)
          return SharedAdaptiveLoop(reader, lastState, energy, dispatcher) to { counter }
      }

      @Test fun `slow consumer drops intermediate ticks, loop never blocked`() = runTest {
          val dispatcher = StandardTestDispatcher(testScheduler)
          val (loop, counter) = buildLoop(dispatcher)
          val scope = TestScope(dispatcher)
          val job = loop.start(scope)

          val seen = mutableListOf<Int>()
          scope.launch {
              loop.flow.collect { d ->
                  seen.add(d.soc!!)
                  delay(10_000)   // way slower than 1s drive cadence
              }
          }
          advanceTimeBy(5_500)        // 5 ticks of drive cadence ~ 5 emissions
          assertEquals(1, seen.size)  // first tick consumed, rest dropped
          assertEquals(5, counter())  // loop progressed regardless
          job.cancel()
      }

      @Test fun `five fast subscribers all observe ticks, none block the loop`() = runTest {
          val dispatcher = StandardTestDispatcher(testScheduler)
          val (loop, counter) = buildLoop(dispatcher)
          val scope = TestScope(dispatcher)
          val job = loop.start(scope)

          val seenLists = List(5) { mutableListOf<Int>() }
          seenLists.forEach { dst ->
              scope.launch { loop.flow.collect { dst.add(it.soc!!) } }
          }
          advanceTimeBy(5_500)
          // Loop emitted 5 ticks; each fast subscriber should have observed them.
          assertEquals(5, counter())
          seenLists.forEach { assertTrue("subscriber received ticks: ${'$'}it", it.size in 4..5) }
          job.cancel()
      }

      @Test fun `late subscriber receives the latest replayed value (replay=1)`() = runTest {
          val dispatcher = StandardTestDispatcher(testScheduler)
          val (loop, _) = buildLoop(dispatcher)
          val scope = TestScope(dispatcher)
          val job = loop.start(scope)
          advanceTimeBy(2_500)   // 2 ticks have been emitted

          val latest = scope.async { loop.flow.first() }.also { advanceTimeBy(10) }.await()
          assertTrue("late subscriber sees a replayed value", latest.soc != null)
          job.cancel()
      }
  }
  ```

- [ ] **Step 2 — Run, confirm pass (no production change expected)**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.loop.SharedFlowConcurrencyTest"
  ```

  If the test fails, the most likely cause is that `MutableSharedFlow` is using extraBufferCapacity > 0; double-check `extraBufferCapacity = 0` and `replay = 1` in `SharedAdaptiveLoop`.

- [ ] **Step 3 — Commit**

  ```bash
  git add app/src/test/kotlin/com/bydmate/app/data/loop/SharedFlowConcurrencyTest.kt
  git commit -m "phase-1.5(loop): regression test — slow subscribers cannot block the loop"
  ```

---

### Task 10: DI providers for SharedAdaptiveLoop

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt` — add `@Provides @Singleton` for `LastStateDao` and `SharedAdaptiveLoop`.

> The class itself already carries `@Singleton @Inject constructor(...)`, so Hilt auto-binds it. Only `LastStateDao` needs an explicit provider next to the existing DAO providers.

- [ ] **Step 1 — Add the DAO provider**

  Append to the `@Provides`-style DAO section in `AppModule.kt`:

  ```kotlin
  @Provides @Singleton
  fun provideLastStateDao(db: AppDatabase): LastStateDao = db.lastStateDao()
  ```

- [ ] **Step 2 — Sanity build**

  ```bash
  ./gradlew :app:assembleDebug
  ```
  > Use `assembleDebug`, not `assembleRelease`. Release APKs are gated on explicit `собирай`.

- [ ] **Step 3 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/di/AppModule.kt
  git commit -m "phase-1.5(di): provide LastStateDao for SharedAdaptiveLoop"
  ```

---

## Group C — TripRecorder

> **Spec-correction callout (codex audit):** `DiParsData.powerState` enum (`DiParsData.kt:21`) is `0=OFF, 1=ON, 2=DRIVE` — NOT the spec's mental model `0=OFF, 1=ACC, 2=ON`. Effective mapping for Phase 1.5:
> - Spec "OFF"  ↔ powerState 0
> - Spec "ACC"  ↔ powerState 1 (ignition on, P/N, not in drive mode)
> - Spec "ON"   ↔ powerState 2 (drive mode engaged)
>
> So: trip start = `powerState 1 → 2`, trip end = `powerState 2 → {0,1}`. `speed` is `Int?` (`DiParsData.kt:5`), `gear` is `Int?` with `1=P, 2=R, 3=N, 4=D` (`DiParsData.kt:20`). All trip-related tasks use these types and integer transitions.
>
> `TripRecorder.consume()` MUST persist open-trip fields to `last_state` on open and clear them on close (spec §96-100). The in-memory `Open` cache is a fast path; `LastStateDao` is the source of truth used for cold-start reconciliation.

### Task 11: TripRecorder — passive mode on Leopard 3

**Files:**
- Create: `app/src/main/kotlin/com/bydmate/app/data/trips/TripRecorder.kt`
- Test: `app/src/test/kotlin/com/bydmate/app/data/trips/TripRecorderPassiveTest.kt`

- [ ] **Step 1 — Test**

  Uses the `diParsData(...)` fixture builder created in Task 7 (`DiParsDataFixtures.kt`) — `DiParsData` has 49 required nullable fields and no default args, so direct `DiParsData(...)` constructor calls won't compile.

  ```kotlin
  package com.bydmate.app.data.trips

  import com.bydmate.app.data.local.EnergyDataReader
  import com.bydmate.app.data.local.dao.LastStateDao
  import com.bydmate.app.data.local.dao.TripDao
  import com.bydmate.app.data.loop.diParsData
  import io.mockk.coVerify
  import io.mockk.every
  import io.mockk.mockk
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.test.runTest
  import org.junit.Test

  @OptIn(ExperimentalCoroutinesApi::class)
  class TripRecorderPassiveTest {
      @Test fun `passive mode never inserts trip rows nor writes open trip to last_state`() = runTest {
          val tripDao = mockk<TripDao>(relaxed = true)
          val lastState = mockk<LastStateDao>(relaxed = true)
          val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
          val recorder = TripRecorder(tripDao, lastState, energy, batteryCapacityKwh = { 72.9 })

          recorder.consume(diParsData(powerState = 1, soc = 80, mileage = 100.0))   // ACC=ON-idle
          recorder.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))   // DRIVE
          recorder.consume(diParsData(powerState = 2, soc = 70, mileage = 110.0))   // driving
          recorder.consume(diParsData(powerState = 1, soc = 70, mileage = 110.0))   // back to ACC

          coVerify(exactly = 0) { tripDao.insert(any()) }
          coVerify(exactly = 0) { lastState.openTrip(any(), any(), any(), any()) }
          coVerify(exactly = 0) { lastState.clearOpenTrip() }
      }
  }
  ```

- [ ] **Step 2 — Confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.trips.TripRecorderPassiveTest"
  ```

- [ ] **Step 3 — Implement skeleton**

  ```kotlin
  package com.bydmate.app.data.trips

  import com.bydmate.app.data.local.EnergyDataReader
  import com.bydmate.app.data.local.dao.LastStateDao
  import com.bydmate.app.data.local.dao.TripDao
  import com.bydmate.app.data.local.entity.TripEntity
  import com.bydmate.app.data.remote.DiParsData
  import javax.inject.Inject
  import javax.inject.Singleton

  @Singleton
  class TripRecorder @Inject constructor(
      private val tripDao: TripDao,
      private val lastStateDao: LastStateDao,
      private val energyDataReader: EnergyDataReader,
      private val batteryCapacityKwh: suspend () -> Double,
      private val now: () -> Long = { System.currentTimeMillis() },
  ) {
      // In-memory mirror of last_state.open_trip_*; rebuilt at cold-start from DAO.
      internal data class Open(
          val startTs: Long,
          val startSoc: Int?,
          val startMileage: Double?,
      )
      internal var open: Open? = null

      suspend fun consume(data: DiParsData) {
          val active = !energyDataReader.isAvailable()
          if (!active) return  // passive on Leopard 3 — never write trips or open-trip state

          val ignitionOn = data.powerState == 2  // DRIVE mode in DiParsData enum
          val cur = open
          when {
              cur == null && ignitionOn -> openTrip(data)
              cur != null && !ignitionOn -> close(cur, data)
          }
      }

      private suspend fun openTrip(data: DiParsData) {
          val startTs = now()
          open = Open(startTs, data.soc, data.mileage)
          // Spec §96-100: persist open trip to last_state so cold-start can resume.
          val updated = lastStateDao.openTrip(
              startTs = startTs,
              startSoc = data.soc,
              startMileage = data.mileage,
              now = startTs,
          )
          if (updated == 0) {
              // Very-first-ever tick before the loop has written a snapshot.
              lastStateDao.upsert(
                  com.bydmate.app.data.local.entity.LastStateEntity(
                      id = 1,
                      ts = startTs,
                      soc = data.soc,
                      mileage = data.mileage,
                      ignition = data.powerState,
                      openTripId = startTs,
                      tripStartTs = startTs,
                      tripStartSoc = data.soc,
                      tripStartMileage = data.mileage,
                      energydataAvailable = 0,
                  )
              )
          }
      }

      private suspend fun close(open: Open, end: DiParsData) {
          val cap = batteryCapacityKwh()
          val socDelta = (open.startSoc ?: 0) - (end.soc ?: 0)
          val kwh = if (socDelta > 0) socDelta / 100.0 * cap else null
          val distance = if (open.startMileage != null && end.mileage != null)
              (end.mileage - open.startMileage).coerceAtLeast(0.0) else null
          tripDao.insert(
              TripEntity(
                  startTs = open.startTs,
                  endTs = now(),
                  distanceKm = distance,
                  kwhConsumed = kwh,
                  socStart = open.startSoc,
                  socEnd = end.soc,
                  source = TripSource.NATIVE_POLLING,
              )
          )
          this.open = null
          lastStateDao.clearOpenTrip()
      }
  }
  ```

  > Required `LastStateDao` additions (define when implementing Task 3 — add to that DAO if not already there):
  > - `suspend fun openTrip(startTs: Long, startSoc: Int?, startMileage: Double?, now: Long)` — UPSERTs row id=1 setting `open_trip_id`, `trip_start_ts`, `trip_start_soc`, `trip_start_mileage`, `ts`.
  > - `suspend fun clearOpenTrip()` — UPDATE row id=1 SET open_trip_id=NULL, trip_start_ts=NULL, trip_start_soc=NULL, trip_start_mileage=NULL.

- [ ] **Step 4 — Pass + commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/trips/TripRecorder.kt \
          app/src/test/kotlin/com/bydmate/app/data/trips/TripRecorderPassiveTest.kt
  git commit -m "phase-1.5(trips): TripRecorder skeleton + last_state open/close hooks"
  ```

---

### Task 12: TripRecorder — active mode, ACC→DRIVE→ACC cycle

**Files:**
- Test: `app/src/test/kotlin/com/bydmate/app/data/trips/TripRecorderActiveTest.kt`

> Reminder: powerState `1` = ACC (ignition on, not driving), `2` = DRIVE. Trip opens on `1→2`, closes on `2→{0,1}`.

- [ ] **Step 1 — Test**

  ```kotlin
  package com.bydmate.app.data.trips

  import com.bydmate.app.data.local.EnergyDataReader
  import com.bydmate.app.data.local.dao.LastStateDao
  import com.bydmate.app.data.local.dao.TripDao
  import com.bydmate.app.data.local.entity.TripEntity
  import com.bydmate.app.data.loop.diParsData
  import io.mockk.coVerify
  import io.mockk.every
  import io.mockk.mockk
  import io.mockk.slot
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Test

  @OptIn(ExperimentalCoroutinesApi::class)
  class TripRecorderActiveTest {

      private fun setup(): Triple<TripRecorder, TripDao, LastStateDao> {
          val tripDao = mockk<TripDao>(relaxed = true)
          val lastState = mockk<LastStateDao>(relaxed = true)
          val energy = mockk<EnergyDataReader> { every { isAvailable() } returns false }
          val clock = mutableListOf(1_000L, 2_000L, 3_000L, 4_000L)
          val recorder = TripRecorder(
              tripDao, lastState, energy,
              batteryCapacityKwh = { 72.9 },
              now = { clock.removeAt(0) },
          )
          return Triple(recorder, tripDao, lastState)
      }

      @Test fun `ACC then DRIVE opens trip, DRIVE to ACC closes with one row + writes last_state`() = runTest {
          val (rec, tripDao, lastState) = setup()
          rec.consume(diParsData(powerState = 1, soc = 80, mileage = 100.0))  // ACC
          rec.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))  // DRIVE -> open(now=1000)
          rec.consume(diParsData(powerState = 2, soc = 70, mileage = 110.0))  // driving (no insert)
          rec.consume(diParsData(powerState = 1, soc = 70, mileage = 110.0))  // ACC -> close(now=2000)

          val captured = slot<TripEntity>()
          coVerify(exactly = 1) { tripDao.insert(capture(captured)) }
          val t = captured.captured
          assertEquals(1_000L, t.startTs)
          assertEquals(2_000L, t.endTs)
          assertEquals(80, t.socStart)
          assertEquals(70, t.socEnd)
          assertEquals(10.0, t.distanceKm!!, 0.001)
          assertEquals(72.9 * 0.10, t.kwhConsumed!!, 0.001)
          assertEquals(TripSource.NATIVE_POLLING, t.source)
          // Spec §96-100: last_state mirrors open trip state.
          coVerify(exactly = 1) { lastState.openTrip(startTs = 1_000L, startSoc = 80, startMileage = 100.0, now = 1_000L) }
          coVerify(exactly = 1) { lastState.clearOpenTrip() }
      }

      @Test fun `consecutive DRIVE ticks do not double-open`() = runTest {
          val (rec, tripDao, lastState) = setup()
          rec.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))
          rec.consume(diParsData(powerState = 2, soc = 79, mileage = 101.0))
          rec.consume(diParsData(powerState = 2, soc = 78, mileage = 102.0))
          rec.consume(diParsData(powerState = 1, soc = 78, mileage = 102.0))
          coVerify(exactly = 1) { tripDao.insert(any()) }
          coVerify(exactly = 1) { lastState.openTrip(any(), any(), any(), any()) }
      }

      @Test fun `DRIVE to OFF also closes (powerState 2 to 0)`() = runTest {
          val (rec, tripDao, lastState) = setup()
          rec.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))  // DRIVE -> open
          rec.consume(diParsData(powerState = 0, soc = 75, mileage = 105.0))  // OFF -> close
          coVerify(exactly = 1) { tripDao.insert(any()) }
          coVerify(exactly = 1) { lastState.clearOpenTrip() }
      }
  }
  ```

- [ ] **Step 2 — Run; the second test should already pass; the first will pass once the implementation respects `open` state and uses `now()` for `endTs`.**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.trips.TripRecorderActiveTest"
  ```

- [ ] **Step 3 — Fix any failures in `TripRecorder` and commit**

  Likely fix: ensure `now()` is called *before* the `tripDao.insert(...)` so the captured `endTs` matches the test's expected `2_000L`. If the skeleton already does this, the test should pass without code change.

  ```bash
  git add app/src/test/kotlin/com/bydmate/app/data/trips/TripRecorderActiveTest.kt \
          app/src/main/kotlin/com/bydmate/app/data/trips/TripRecorder.kt
  git commit -m "phase-1.5(trips): active mode ACC->ON opens trip, ON->ACC closes once"
  ```

---

### Task 13: TripRecorder — `powerState` sentinel fallback

**Files:**
- Test: `app/src/test/kotlin/com/bydmate/app/data/trips/PowerStateFallbackTest.kt`

- [ ] **Step 1 — Test**

  ```kotlin
  package com.bydmate.app.data.trips

  import com.bydmate.app.data.local.EnergyDataReader
  import com.bydmate.app.data.local.dao.LastStateDao
  import com.bydmate.app.data.local.dao.TripDao
  import com.bydmate.app.data.loop.diParsData
  import io.mockk.coVerify
  import io.mockk.every
  import io.mockk.mockk
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.test.runTest
  import org.junit.Test

  @OptIn(ExperimentalCoroutinesApi::class)
  class PowerStateFallbackTest {
      @Test fun `after 5 null powerState ticks, gear D acts as ignition DRIVE`() = runTest {
          val tripDao = mockk<TripDao>(relaxed = true)
          val ls = mockk<LastStateDao>(relaxed = true)
          val en = mockk<EnergyDataReader> { every { isAvailable() } returns false }
          val rec = TripRecorder(tripDao, ls, en, batteryCapacityKwh = { 72.9 })

          // 5 ticks with powerState null → fallback armed.
          repeat(5) { rec.consume(diParsData(powerState = null, gear = 1, soc = 80, mileage = 100.0)) }
          // Gear D (drive) opens a trip.
          rec.consume(diParsData(powerState = null, gear = 4, soc = 80, mileage = 100.0, speed = 10))
          // Gear P (park) ends it.
          rec.consume(diParsData(powerState = null, gear = 1, soc = 75, mileage = 105.0, speed = 0))

          coVerify(exactly = 1) { tripDao.insert(any()) }
      }
  }
  ```

- [ ] **Step 2 — Confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.trips.PowerStateFallbackTest"
  ```

- [ ] **Step 3 — Implement fallback in `TripRecorder`**

  Augment `consume`. Note `data.speed` is `Int?` (`DiParsData.kt:5`), not Double:

  ```kotlin
  private var nullPowerStreak = 0
  private fun ignitionOn(data: DiParsData): Boolean {
      if (data.powerState != null) { nullPowerStreak = 0; return data.powerState == 2 }
      nullPowerStreak++
      val fallback = nullPowerStreak >= 5
      return fallback && (data.gear == 4 || ((data.speed ?: 0) > 0))
  }
  ```

  Replace `val ignitionOn = data.powerState == 2` with `val ignitionOn = ignitionOn(data)`.

- [ ] **Step 4 — Pass + commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/trips/TripRecorder.kt \
          app/src/test/kotlin/com/bydmate/app/data/trips/PowerStateFallbackTest.kt
  git commit -m "phase-1.5(trips): fallback to gear/speed after 5 null powerState ticks"
  ```

---

### Task 14: TripRecorder — cold-start reconciliation

**Files:**
- Test: `app/src/test/kotlin/com/bydmate/app/data/trips/ColdStartReconciliationTest.kt`

This test covers `TripRecorder.reconcileColdStart(...)`, called by `TrackingService` before subscribing to the loop.

- [ ] **Step 1 — Test**

  ```kotlin
  package com.bydmate.app.data.trips

  import com.bydmate.app.data.local.EnergyDataReader
  import com.bydmate.app.data.local.dao.LastStateDao
  import com.bydmate.app.data.local.dao.TripDao
  import com.bydmate.app.data.local.entity.LastStateEntity
  import com.bydmate.app.data.local.entity.TripEntity
  import io.mockk.coEvery
  import io.mockk.coVerify
  import io.mockk.every
  import io.mockk.mockk
  import io.mockk.slot
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Test

  @OptIn(ExperimentalCoroutinesApi::class)
  class ColdStartReconciliationTest {

      private fun energyAvailable(b: Boolean) =
          mockk<EnergyDataReader> { every { isAvailable() } returns b }

      @Test fun `gap less than 5 minutes resumes the open trip`() = runTest {
          val tripDao = mockk<TripDao>(relaxed = true)
          val lastState = mockk<LastStateDao>(relaxed = true) {
              coEvery { getCurrent() } returns LastStateEntity(
                  id = 1, ts = 1_000_000L, soc = 70, mileage = 110.0,
                  openTripId = 99L, tripStartTs = 900_000L, tripStartSoc = 80,
                  tripStartMileage = 100.0
              )
          }
          val rec = TripRecorder(
              tripDao, lastState, energyAvailable(false),
              batteryCapacityKwh = { 72.9 },
              now = { 1_000_000L + 4 * 60_000L }   // 4 min later
          )
          rec.reconcileColdStart()
          coVerify(exactly = 0) { tripDao.insert(any()) }
      }

      @Test fun `gap 5+ minutes finalises stale trip`() = runTest {
          val tripDao = mockk<TripDao>(relaxed = true)
          val lastState = mockk<LastStateDao>(relaxed = true) {
              coEvery { getCurrent() } returns LastStateEntity(
                  id = 1, ts = 1_000_000L, soc = 70, mileage = 110.0,
                  openTripId = 99L, tripStartTs = 900_000L, tripStartSoc = 80,
                  tripStartMileage = 100.0
              )
          }
          val rec = TripRecorder(
              tripDao, lastState, energyAvailable(false),
              batteryCapacityKwh = { 72.9 },
              now = { 1_000_000L + 10 * 60_000L }
          )
          rec.reconcileColdStart()
          val cap = slot<TripEntity>()
          coVerify(exactly = 1) { tripDao.insert(capture(cap)) }
          assertEquals(900_000L, cap.captured.startTs)
          assertEquals(1_000_000L, cap.captured.endTs)
          assertEquals(80, cap.captured.socStart)
          assertEquals(70, cap.captured.socEnd)
          coVerify { lastState.clearOpenTrip() }
      }

      @Test fun `no last_state row starts fresh`() = runTest {
          val tripDao = mockk<TripDao>(relaxed = true)
          val lastState = mockk<LastStateDao>(relaxed = true) { coEvery { getCurrent() } returns null }
          val rec = TripRecorder(tripDao, lastState, energyAvailable(false), batteryCapacityKwh = { 72.9 })
          rec.reconcileColdStart()
          coVerify(exactly = 0) { tripDao.insert(any()) }
      }

      @Test fun `passive mode (energydata available) never inserts even when openTripId set`() = runTest {
          val tripDao = mockk<TripDao>(relaxed = true)
          val lastState = mockk<LastStateDao>(relaxed = true) {
              coEvery { getCurrent() } returns LastStateEntity(
                  id = 1, ts = 1_000_000L, openTripId = 99L,
                  tripStartTs = 900_000L, tripStartSoc = 80, tripStartMileage = 100.0
              )
          }
          val rec = TripRecorder(
              tripDao, lastState, energyAvailable(true),
              batteryCapacityKwh = { 72.9 },
              now = { 1_000_000L + 10 * 60_000L }
          )
          rec.reconcileColdStart()
          coVerify(exactly = 0) { tripDao.insert(any()) }
          coVerify { lastState.clearOpenTrip() }
      }
  }
  ```

- [ ] **Step 2 — Confirm failure**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.trips.ColdStartReconciliationTest"
  ```

- [ ] **Step 3 — Implement `reconcileColdStart`**

  Append to `TripRecorder.kt`:

  ```kotlin
  /** Call once before subscribing to the loop. */
  suspend fun reconcileColdStart() {
      val state = lastStateDao.getCurrent() ?: return
      if (state.openTripId == null || state.tripStartTs == null) return
      val gap = now() - state.ts
      val active = !energyDataReader.isAvailable()
      val staleGap = 5 * 60 * 1_000L
      if (gap < staleGap) {
          // resume: open in-memory; the next consume() tick will continue.
          if (active) {
              open = Open(state.tripStartTs, state.tripStartSoc, state.tripStartMileage)
          }
          return
      }
      // stale → finalize
      if (active) {
          val socDelta = (state.tripStartSoc ?: 0) - (state.soc ?: 0)
          val kwh = if (socDelta > 0) socDelta / 100.0 * batteryCapacityKwh() else null
          val distance = if (state.tripStartMileage != null && state.mileage != null)
              (state.mileage - state.tripStartMileage).coerceAtLeast(0.0) else null
          tripDao.insert(
              TripEntity(
                  startTs = state.tripStartTs,
                  endTs = state.ts,
                  distanceKm = distance,
                  kwhConsumed = kwh,
                  socStart = state.tripStartSoc,
                  socEnd = state.soc,
                  source = TripSource.NATIVE_POLLING,
              )
          )
      }
      lastStateDao.clearOpenTrip()
  }
  ```

- [ ] **Step 4 — Pass + commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/trips/TripRecorder.kt \
          app/src/test/kotlin/com/bydmate/app/data/trips/ColdStartReconciliationTest.kt
  git commit -m "phase-1.5(trips): cold-start reconciliation with 5-min stale threshold"
  ```

---

### Task 15: TripRecorder — Hilt provider + DI binding

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/di/AppModule.kt`

- [ ] **Step 1 — Provide `batteryCapacityKwh`**

  Reuse the existing settings or constant in the app for battery capacity. There is a `feedback_leopard3_capacity` note pinning Leopard 3 at 72.9 kWh; for Song/Atto users this value is wrong and must be configurable later. For Phase 1.5 read it from `SettingsRepository.batteryCapacityKwh` if exposed, else hardcode 72.9 and add a `TODO(phase-1.5+)` comment to expose it in Settings later.

  Add a `@Provides @Singleton` for `TripRecorder` that injects `TripDao`, `LastStateDao`, `EnergyDataReader`, and a `() -> Double` lambda capturing `SettingsRepository`:

  `SettingsRepository.getBatteryCapacity()` is already `suspend` (`SettingsRepository.kt:101`) and `TripRecorder.batteryCapacityKwh` is typed `suspend () -> Double`. Pass it directly — no blocking accessor needed:

  ```kotlin
  @Provides @Singleton
  fun provideTripRecorder(
      tripDao: TripDao,
      lastStateDao: LastStateDao,
      energyDataReader: EnergyDataReader,
      settingsRepository: SettingsRepository,
  ): TripRecorder = TripRecorder(
      tripDao = tripDao,
      lastStateDao = lastStateDao,
      energyDataReader = energyDataReader,
      batteryCapacityKwh = { settingsRepository.getBatteryCapacity() },
  )
  ```

- [ ] **Step 2 — Sanity build**

  ```bash
  ./gradlew :app:assembleDebug
  ```

- [ ] **Step 3 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/di/AppModule.kt
  git commit -m "phase-1.5(di): provide TripRecorder with battery capacity binding"
  ```

---

## Group D — TrackingService swap

### Task 16: Replace TrackingService.startPolling body with SharedAdaptiveLoop subscription

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt:626-766`

This is the largest single change. The strategy: keep every downstream call (`maybeSendIternioTelemetry`, `alicePollingManager.latestData = data`, charging detector feeds, automation triggers, overlays, range/SOC interpolators) and only swap the *source* of `data` from `parsReader.fetch()` + bespoke delay/backoff into a `collect { data -> ... }` on the shared flow.

- [ ] **Step 1 — Inject the loop and recorder**

  Add to the existing `@Inject lateinit var` block at the top of `TrackingService`:

  ```kotlin
  @Inject lateinit var sharedAdaptiveLoop: SharedAdaptiveLoop
  @Inject lateinit var tripRecorder: TripRecorder
  ```

- [ ] **Step 2 — Replace the loop body**

  The current `startPolling()` (around `:626-766`) contains a `while (isActive) { val data = parsReader.fetch(); if (data == null) { backoff } else { ... } }` structure. Replace it with the block below. The downstream pipeline below is the **complete, verbatim** list of consumers from `TrackingService.kt:636-750` — the implementer MUST preserve every one of these calls in this order, only swapping the data source. Do NOT use ellipsis; if you cannot find a call from this list in the current file (e.g. it was removed in Этап A), flag it as a discrepancy before continuing.

  ```kotlin
  private fun startPolling() {
      pollJob?.cancel()
      pollJob = serviceScope.launch {
          // Cold-start reconciliation BEFORE subscribing — so we never receive
          // a tick into a stale open trip from a previous session.
          runCatching { tripRecorder.reconcileColdStart() }
              .onFailure { Log.w(TAG, "Cold-start reconciliation failed", it) }

          sharedAdaptiveLoop.start(serviceScope)

          launch {
              sharedAdaptiveLoop.connected.collect { connected ->
                  _vehicleDataConnected.value = connected
              }
          }

          var firstDataReceived = false
          var pollTickCount = 0L

          sharedAdaptiveLoop.flow.collect { data ->
              val nowMs = System.currentTimeMillis()
              try {
                  // === BEGIN preserved downstream pipeline (verbatim from :636-750) ===
                  // 1. connectivity + latestData mirror
                  _lastData.value = data
                  alicePollingManager.latestData = data

                  // 2. settings + SOC bookmarks
                  data.soc?.let { settingsRepository.saveLastKnownSoc(it) }

                  // 3. charging — power accumulator + gun-state edge poll (throttled)
                  data.power?.let { observedChargingPowerKwAbs = kotlin.math.abs(it.toDouble()) }
                  pollTickCount++
                  if (pollTickCount % GUN_STATE_POLL_EVERY_N_TICKS == 0L) {
                      pollGunStateForEdge(data)
                  }

                  // 4. one-shot first-data effects (offline charge detection)
                  if (!firstDataReceived) {
                      firstDataReceived = true
                      detectOfflineCharge(data)
                  }

                  // 5. GPS / TripTracker feed (energydata mode)
                  tripTracker.onData(data)

                  // 6. session bookkeeping
                  updateSessionState(data, nowMs)

                  // 7. odometer + live-trip ring buffers
                  odometerBuffer.onSample(data, nowMs)
                  liveTripBuffer.onSample(data, nowMs)
                  socInterpolator.onSample(data, nowMs)

                  // 8. recent / short consumption averages
                  recentAvgConsumption.onSample(data, nowMs)
                  shortAvgConsumption.onSample(data, nowMs)

                  // 9. trip distance + kWh accumulators
                  tripDistance.onSample(data, nowMs)
                  tripKwhConsumed.onSample(data, nowMs)

                  // 10. dashboard "big numbers" + aggregator + range
                  BigNumberCalculator.computeDisplay(data, nowMs, /* etc. */)
                  ConsumptionAggregator.onSample(data, nowMs)
                  rangeCalculator.estimate(data)

                  // 11. persistent session snapshot
                  sessionPersistence.save(data, nowMs)

                  // 12. automation + UX outputs
                  automationEngine.evaluate(data, nowMs)
                  updateNotification(data)
                  maybeLogSessionSummary(data, nowMs)
                  maybeSendIternioTelemetry(data, nowMs)

                  // 13. charging detector (replaces its internal poll — see Task 17)
                  autoserviceChargingDetector.onSample(data)

                  // 14. native trip recorder (writes only when energydata absent)
                  tripRecorder.consume(data)
                  // === END preserved downstream pipeline ===
              } catch (t: Throwable) {
                  // One consumer failing must not break the subscription.
                  Log.w(TAG, "downstream consumer threw on tick", t)
              }
          }
      }
  }
  ```

  Remove the previous `consecutivePollFailures`, `currentPollIntervalMs`, `POLL_INTERVAL_MS`, `MAX_POLL_INTERVAL_MS`, and `NULL_WARNING_THRESHOLD` constants — they now live in `SharedAdaptiveLoop`/`CadenceConfig`. Remove the `parsReader.fetch()` call and the `delay(...)` lines inside the old loop. Keep `GUN_STATE_POLL_EVERY_N_TICKS` as-is (still used).

  > **Pipeline preservation check (run BEFORE committing):** every call listed above must exist in the current `TrackingService.kt` before this task starts. If any disappeared during Этап A, stop and reconcile — do not silently drop. Conversely, if `TrackingService.kt:626-766` contains a call not on this list, add it to the block (and update this plan in the same commit).

- [ ] **Step 3 — Sanity build**

  ```bash
  ./gradlew :app:assembleDebug
  ```

- [ ] **Step 4 — Update existing `TrackingServiceTest` (if any)**

  If a unit/instrumentation test stubs `parsReader.fetch()` directly to drive the service, replace it with stubbing `sharedAdaptiveLoop.flow` via a fake. Skip this step if no such test exists.

- [ ] **Step 5 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt
  git commit -m "phase-1.5(service): swap bespoke poll loop for SharedAdaptiveLoop subscription"
  ```

---

### Task 17: `AutoserviceChargingDetector` — accept ticks from outside

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt`

The detector currently runs its own `parsReader.fetch()` loop (lines 141, 314). Phase 1.5 keeps the detector's *logic* but feeds it from the shared flow via a new `onSample(data: DiParsData)` entry point. Internal polling is removed.

- [ ] **Step 1 — Add `onSample` entry point**

  Wrap the detector's existing decision pipeline in a function that accepts a single `DiParsData` and runs the logic that was previously inside the polling body. Where the previous code called `parsReader.fetch()`, the data is now the argument.

  Delete the internal polling loop (any `while (isActive) { delay(...); parsReader.fetch() }` block). The class becomes purely event-driven.

- [ ] **Step 2 — Adapt or delete the internal polling test if one exists**

  Replace it with a focused test that calls `onSample(...)` directly with a sequence of `DiParsData` snapshots covering: disconnected→connected charging start, connected→disconnected stop, voltage threshold, gun state sentinel.

- [ ] **Step 3 — Wire into TrackingService**

  In `TrackingService.startPolling()` add `autoserviceChargingDetector.onSample(data)` inside the `collect { ... }` (already drafted in Task 16). Remove any `autoserviceChargingDetector.start(...)` call that kicked off its own coroutine; replace with whatever lifecycle hook the detector still needs (e.g., flushing buffered samples on stop).

- [ ] **Step 4 — Sanity build + targeted test**

  ```bash
  ./gradlew :app:assembleDebug
  ./gradlew :app:testDebugUnitTest --tests "com.bydmate.app.data.charging.*"
  ```

- [ ] **Step 5 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/charging/AutoserviceChargingDetector.kt \
          app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt
  git commit -m "phase-1.5(charging): AutoserviceChargingDetector consumes samples instead of polling"
  ```

---

### Task 18: `AlicePollingManager` — collect flow instead of `delay(POLL_INTERVAL_MS)`

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/data/remote/AlicePollingManager.kt`

`AlicePollingManager` has an internal `delay(POLL_INTERVAL_MS)` loop at line 58. The VPS POST cadence (~25 s) stays the same; we only switch the data source for `latestData`.

- [ ] **Step 1 — Add a `SharedAdaptiveLoop` collaborator**

  In the Alice manager's constructor, accept `sharedAdaptiveLoop: SharedAdaptiveLoop` (Hilt singleton). Inside `start()`:
  - Launch a coroutine that does `sharedAdaptiveLoop.flow.collect { latestData = it }`.
  - The existing VPS POST timer (its own `delay`) stays as-is — but its inner read replaces `parsReader.fetch()`/etc. with `latestData`.
  - Remove the now-redundant 2.5 s polling loop.

- [ ] **Step 2 — Sanity build**

  ```bash
  ./gradlew :app:assembleDebug
  ```

- [ ] **Step 3 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/data/remote/AlicePollingManager.kt
  git commit -m "phase-1.5(alice): replace internal polling with flow collector"
  ```

  > If `TrackingService.startPolling()` previously did `alicePollingManager.latestData = data`, that line can stay as a defensive write — Alice will see the same value from both paths.

---

### Task 19: `DashboardViewModel` + `SettingsViewModel` consume the flow

**Files:**
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt#runDiagnostics`

If Dashboard already gets data through a repository that buffers what `TrackingService` writes (e.g., via a `VehicleDataRepository` `MutableStateFlow`), redirect the repository to subscribe to `sharedAdaptiveLoop.flow` and keep call sites unchanged.

If Dashboard had its own `parsReader.fetch()` call (unlikely, based on the grep that returned no hits earlier), replace it with `sharedAdaptiveLoop.flow.collectLatest { data -> /* update UI state */ }` in `init { ... }` and remove any timer.

For `SettingsViewModel.runDiagnostics()`: replace any ad-hoc `parsReader.fetch()` call with `sharedAdaptiveLoop.flow.first()` (which awaits the next emission, up to a `withTimeout(5_000)` to keep the UI responsive). If the call site needs `null` semantics, wrap in `runCatching { withTimeoutOrNull(5_000) { sharedAdaptiveLoop.flow.first() } }`.

- [ ] **Step 1 — Apply the changes (small, targeted)**

- [ ] **Step 2 — Sanity build**

  ```bash
  ./gradlew :app:assembleDebug
  ```

- [ ] **Step 3 — Commit**

  ```bash
  git add app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt \
          app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt
  git commit -m "phase-1.5(ui): Dashboard + Settings diagnostics consume SharedAdaptiveLoop.flow"
  ```

---

### ~~Task 20: `HistoryImporter` — TripSource.ENERGYDATA + source-aware dedup~~ DELETED

> **Codex audit (blocking) — removed from Phase 1.5 scope.** Spec §scope/non-goals (`spec-phase-1.5-shared-loop-2026-05-27.md:35-39`) explicitly forbids touching the Leopard 3 energydata import pipeline. The `"energydata"` string literal in `HistoryImporter.kt` is a wire value persisted in v1.x trip rows; replacing it with `TripSource.ENERGYDATA` is a refactor with zero behavior change and a non-zero risk of breaking dedup against rows written by older builds. Defer to a separate cleanup PR after Phase 1.5 ships.
>
> Dedup change is also out of scope: `TripRecorder` only writes `source = NATIVE_POLLING` on non-Leopard 3 devices where no `energydata` rows exist, so collision is impossible by construction.
>
> What stays: the `TripSource.ENERGYDATA = "energydata"` constant from Task 1 is still defined (re-exports the wire value for type-safety in NEW code). Existing `HistoryImporter.kt` keeps the string literal as-is.

---

## Group E — Final integration

### Task 21: Manual lint pass + zero-hit grep gate

**Files:** none new

- [ ] **Step 1 — Confirm no more direct `parsReader.fetch()` in app/src/main outside `SharedAdaptiveLoop`**

  ```bash
  grep -rn 'parsReader\.fetch' app/src/main/kotlin || true
  ```
  Expected: only the line inside `SharedAdaptiveLoop.kt`. Any hit anywhere else means a consumer was missed — go back and migrate it.

- [ ] **Step 2 — Confirm no `delay(POLL_INTERVAL_MS)` style internal pollers remain**

  ```bash
  grep -rnE 'while *\(isActive\)\s*\{[^}]*delay' app/src/main/kotlin || true
  ```
  Inspect any hit; should be empty for vehicle-data polling.

- [ ] **Step 3 — Run all unit tests**

  ```bash
  ./gradlew :app:testDebugUnitTest
  ```

- [ ] **Step 4 — Build debug APK to confirm packaging clean**

  ```bash
  ./gradlew :app:assembleDebug
  ```

- [ ] **Step 5 — Commit any drive-by fixes from the lint pass**

  ```bash
  git status
  # If anything changed:
  git commit -am "phase-1.5(cleanup): zero-hit gate fixes from grep audit"
  ```

---

### Task 22: Smoke checklist file for Andy

**Files:**
- Create: `docs/native-data-stack/smoke-phase-1.5-2026-05-27.md`

Smoke is operator-in-car. The checklist is the deliverable; the actual smoke happens after we say `собирай`.

- [ ] **Step 1 — Write checklist**

  ```markdown
  # Phase 1.5 Smoke — Leopard 3 only

  Build: vX.Y.Z (debug or release per Andy)
  Tester: Andy (real DiLink)

  ## Pre-flight
  - [ ] APK pushed to /sdcard/Download and installed manually via file manager.
  - [ ] Settings → Diagnostics shows live data (sanity).

  ## Drive cycle (1 trip)
  - [ ] Start car, drive ~5 min, park.
  - [ ] In BYDMate Поездки tab — new row appears.
  - [ ] Row.source = "energydata" (Leopard 3 path unchanged).
  - [ ] No duplicate row with source = "native_polling".

  ## Service restart mid-drive
  - [ ] Start drive, then force-stop BYDMate service via Settings → Apps.
  - [ ] Restart service via BootReceiver tap / phone reboot.
  - [ ] Resume the drive 1–2 min later.
  - [ ] One row in Поездки, no duplicates.

  ## Cold-boot recovery
  - [ ] Reboot DiLink mid-drive (or kill car ignition while service active).
  - [ ] Power back on, drive a bit more.
  - [ ] Stale trip closed on cold start; new trip starts cleanly.

  ## Charging
  - [ ] Plug in charger.
  - [ ] FSM enters CHARGE state (cadence 5 s — verify via logcat `SharedAdaptiveLoop` tag).
  - [ ] No row added to Поездки during charge.

  ## Dashboard refresh
  - [ ] Driving: live values update every ~1 s.
  - [ ] Parked with ignition: ~5 s refresh.
  - [ ] Ignition off: ~30 s refresh.

  ## Alice (private build only)
  - [ ] VPS POST every ~25 s carries fresh state (check pm2 logs on vlad-brain).

  ## ABRP/Iternio
  - [ ] Driving: telemetry posts every 1 s.
  - [ ] Parked: every 30 s.

  ## What to flag
  - Any duplicate trip row, missing kwh, or wrong source — report verbatim row.
  - Any stale "open trip" that never closes — note timestamps.
  - Any logcat WARN from `TripRecorder` or `SharedAdaptiveLoop` — paste line.
  ```

- [ ] **Step 2 — Commit**

  ```bash
  git add docs/native-data-stack/smoke-phase-1.5-2026-05-27.md
  git commit -m "phase-1.5(docs): smoke checklist for Leopard 3"
  ```

---

### Task 23: Final code review subagent dispatch

This task is run by the orchestrator (you), not by an implementer subagent.

- [ ] **Step 1 — Dispatch a final reviewer**

  Use `superpowers:requesting-code-review` with the full diff `git log feature/native-data-stack ^main` after Tasks 1-22 are merged on the branch. Reviewer asked to verify:
  - All consumers route through `sharedAdaptiveLoop.flow`.
  - `TripRecorder` is passive on Leopard 3 (energydata available).
  - Migration v13→v14 is additive, has test coverage.
  - No release-debuggable hooks left over.
  - No `parsReader.fetch()` outside `SharedAdaptiveLoop`.

- [ ] **Step 2 — Address any blocking findings**

  Implementer subagent applies fixes per reviewer feedback. Re-dispatch reviewer until ✅.

- [ ] **Step 3 — Stop**

  Do NOT bump versionCode/versionName. Do NOT build release APK. Do NOT create GitHub release. These are gated on explicit `собирай` from Andy after smoke.

---

## Self-review

**Spec coverage check:**
- Goal: shared loop + native trips on non-Leopard-3 — covered by Tasks 8–18 (loop) + 11–14 (TripRecorder).
- FSM cadence drive/charge/parked/idle 1/5/5/30 s — Tasks 6, 7, 8.
- `SharedFlow(replay=1, DROP_OLDEST)` — Task 8.
- `last_state` persistence + cold-start reconciliation — Tasks 2, 3, 4, 14.
- `TripRecorder` passive/active gated on `EnergyDataReader.isAvailable()` — Tasks 5, 11.
- `powerState` sentinel fallback to gear/speed — Task 13.
- HistoryImporter dedup considers `source` — Task 20.
- Migration v13→v14 additive, with v13 fixture — Task 4.
- All consumers (Dashboard, Settings diagnostics, ChargingDetector, Alice, Iternio) routed through the flow — Tasks 16, 17, 18, 19. (Iternio already lives inside `TrackingService.maybeSendIternioTelemetry`, so it is automatically driven by the new `collect` in Task 16; no separate task needed.)
- Smoke checklist — Task 22.

**Placeholder scan:** none — every step has concrete code or commands.

**Type consistency:**
- `LoopState`/`CadenceConfig`/`LoopFsm` used consistently across Tasks 6, 7, 8.
- `TripRecorder.consume(data: DiParsData)` and `reconcileColdStart()` referenced identically in Tasks 11–16.
- `LastStateEntity.openTripId`/`tripStartTs`/etc. column names match between entity (Task 2), DAO (Task 3), migration SQL (Task 4), persistSnapshot (Task 8), and reconcile (Task 14).
- `TripSource.NATIVE_POLLING` literal `"native_polling"` matches across Tasks 1, 11, 14.

**Open caveat:** `Migration13To14Test` requires an actual v13 DB fixture at `app/src/androidTest/assets/migration-fixtures/db-v13.db`. If the implementer subagent cannot pull it from the device, fall back to the JVM-side dump script described in Task 4 Step 1 — both produce equivalent fixtures.

---

## Execution Handoff

Plan complete and saved to `docs/native-data-stack/plan-phase-1.5-2026-05-27.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch one fresh implementer per task (model: sonnet), two-stage review (spec → quality) between tasks, all in this session.
2. **Inline Execution** — execute tasks sequentially here using `superpowers:executing-plans` with batch checkpoints.

Which approach?

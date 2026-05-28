package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration13to14Test {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `migrate creates empty last_state table and validates v14 schema`() {
        helper.createDatabase(dbName, 13).close()
        val migrated = helper.runMigrationsAndValidate(
            dbName, 14, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_13_14
        )

        migrated.query("SELECT count(*) FROM last_state").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun `existing rows survive migration`() {
        helper.createDatabase(dbName, 13).apply {
            execSQL(
                """
                INSERT INTO trips (start_ts, distance_km, kwh_consumed, source, byd_id)
                VALUES (1700000000000, 12.5, 2.4, 'energydata', 42)
                """
            )
            execSQL(
                """
                INSERT INTO trip_points (trip_id, timestamp, lat, lon)
                VALUES (1, 1700000060000, 53.9, 27.5)
                """
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(
            dbName, 14, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_13_14
        )

        migrated.query("SELECT byd_id FROM trips").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(42L, c.getLong(0))
        }
        migrated.query("SELECT trip_id, timestamp FROM trip_points").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1L, c.getLong(0))
            assertEquals(1700000060000L, c.getLong(1))
        }
        migrated.close()
    }

    @Test
    fun `v2_7 chain migration v12 to v14 succeeds with rows preserved`() {
        // External users upgrading from v2.7.x (schema v12) skip straight to v14.
        // Room runs MIGRATION_12_13 then MIGRATION_13_14 in sequence.
        helper.createDatabase(dbName, 12).apply {
            execSQL(
                """
                INSERT INTO trips (start_ts, distance_km, kwh_consumed, source, byd_id)
                VALUES (1700000000000, 12.5, 2.4, 'energydata', 42)
                """
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(
            dbName, 14, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_12_13,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_13_14
        )

        migrated.query("SELECT byd_id FROM trips").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(42L, c.getLong(0))
        }
        migrated.query("SELECT count(*) FROM last_state").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun `last_state columns match entity schema`() {
        helper.createDatabase(dbName, 13).close()
        val migrated = helper.runMigrationsAndValidate(
            dbName, 14, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_13_14
        )

        val cols = mutableSetOf<String>()
        migrated.query("PRAGMA table_info('last_state')").use { c ->
            val nameCol = c.getColumnIndex("name")
            while (c.moveToNext()) cols += c.getString(nameCol)
        }
        val expected = setOf(
            "id", "ts", "soc", "mileage", "ignition",
            "open_trip_id", "trip_start_ts", "trip_start_soc",
            "trip_start_mileage", "energydata_available"
        )
        assertTrue("missing columns: ${expected - cols}", cols.containsAll(expected))
        migrated.close()
    }
}

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
class Migration12to13Test {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `migrate creates byd_id index on trips`() {
        helper.createDatabase(dbName, 12).close()
        val migrated = helper.runMigrationsAndValidate(
            dbName, 13, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_12_13
        )

        val indices = mutableListOf<String>()
        migrated.query("PRAGMA index_list('trips')").use { c ->
            val nameCol = c.getColumnIndex("name")
            while (c.moveToNext()) indices += c.getString(nameCol)
        }
        assertTrue("index_trips_byd_id missing: $indices", "index_trips_byd_id" in indices)
        migrated.close()
    }

    @Test
    fun `migrate creates timestamp index on trip_points`() {
        helper.createDatabase(dbName, 12).close()
        val migrated = helper.runMigrationsAndValidate(
            dbName, 13, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_12_13
        )

        val indices = mutableListOf<String>()
        migrated.query("PRAGMA index_list('trip_points')").use { c ->
            val nameCol = c.getColumnIndex("name")
            while (c.moveToNext()) indices += c.getString(nameCol)
        }
        assertTrue("index_trip_points_timestamp missing: $indices", "index_trip_points_timestamp" in indices)
        migrated.close()
    }

    @Test
    fun `existing rows survive migration`() {
        helper.createDatabase(dbName, 12).apply {
            execSQL("""
                INSERT INTO trips (start_ts, distance_km, kwh_consumed, source, byd_id)
                VALUES (1700000000000, 12.5, 2.4, 'energydata', 42)
            """)
            execSQL("""
                INSERT INTO trip_points (trip_id, timestamp, lat, lon)
                VALUES (1, 1700000060000, 53.9, 27.5)
            """)
            close()
        }
        val migrated = helper.runMigrationsAndValidate(
            dbName, 13, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_12_13
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
}

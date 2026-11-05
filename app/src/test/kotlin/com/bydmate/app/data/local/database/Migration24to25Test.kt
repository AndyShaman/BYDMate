package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration24to25Test {

    private val dbName = "migration-test-24-25.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `24 to 25 keeps every trip point and adds an empty battery power`() {
        helper.createDatabase(dbName, 24).use { old ->
            old.execSQL(
                "INSERT INTO trip_points (trip_id, timestamp, lat, lon, speed_kmh, altitude) " +
                    "VALUES (1, 1000, 53.5, 27.5, 42.0, 250.0)"
            )
            old.execSQL(
                "INSERT INTO trip_points (trip_id, timestamp, lat, lon, speed_kmh, altitude) " +
                    "VALUES (1, 2000, 53.6, 27.6, 55.0, 251.0)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 25, true, AppModule.MIGRATION_24_25)

        migrated.query(
            "SELECT trip_id, timestamp, lat, lon, speed_kmh, altitude, battery_power_w " +
                "FROM trip_points ORDER BY timestamp"
        ).use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals(1L, c.getLong(0)); assertEquals(1000L, c.getLong(1))
            assertEquals(53.5, c.getDouble(2), 1e-9); assertEquals(27.5, c.getDouble(3), 1e-9)
            assertEquals(42.0, c.getDouble(4), 1e-9); assertEquals(250.0, c.getDouble(5), 1e-9)
            assertTrue(c.isNull(6))
            c.moveToNext()
            assertEquals(1L, c.getLong(0)); assertEquals(2000L, c.getLong(1))
            assertEquals(53.6, c.getDouble(2), 1e-9); assertEquals(27.6, c.getDouble(3), 1e-9)
            assertEquals(55.0, c.getDouble(4), 1e-9); assertEquals(251.0, c.getDouble(5), 1e-9)
            assertTrue(c.isNull(6))
        }
        migrated.close()
    }
}

package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModuleMigrationsForTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seamless-upgrade proof: a user who already has BYDMate installed (own bydmate.db
 * full of accumulated trips/charges) must keep every row when upgrading to the
 * native-data-stack build, with no reinstall. Removing the D+ dependency does not
 * touch bydmate.db — D+ was only an external enrichment source.
 *
 * This holds regardless of whether the car exposes the native energydata DB:
 * energydata is a sync SOURCE for new trips, not where existing trips live, so an
 * old-firmware car with no energydata still keeps (and displays) its stored rows.
 * The migration just creates the empty native-stack tables alongside the old data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration13to15Test {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `13 to 15 preserves trips charges trip_points and creates native tables empty`() {
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
            execSQL(
                """
                INSERT INTO charges (start_ts, status, merged_count, kwh_charged, type)
                VALUES (1699000000000, 'FINISHED', 1, 18.7, 'AC')
                """
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 15, true,
            AppModuleMigrationsForTest.MIGRATION_13_14,
            AppModuleMigrationsForTest.MIGRATION_14_15,
        )

        migrated.query("SELECT byd_id FROM trips").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(42L, c.getLong(0))
        }
        migrated.query("SELECT trip_id FROM trip_points").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1L, c.getLong(0))
        }
        migrated.query("SELECT kwh_charged FROM charges").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(18.7, c.getDouble(0), 0.001)
        }
        // Native-stack tables exist and start empty — old data lives beside them.
        migrated.query("SELECT count(*) FROM last_state").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        migrated.query("SELECT count(*) FROM vehicle_write_log").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun `v2_7 chain 12 to 15 preserves trips`() {
        // Older installs (schema v12) jump straight to v15: Room runs 12_13, 13_14,
        // 14_15 in sequence. Accumulated trips must still survive end to end.
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
            dbName, 15, true,
            AppModuleMigrationsForTest.MIGRATION_12_13,
            AppModuleMigrationsForTest.MIGRATION_13_14,
            AppModuleMigrationsForTest.MIGRATION_14_15,
        )

        migrated.query("SELECT byd_id FROM trips").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(42L, c.getLong(0))
        }
        migrated.close()
    }
}

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
class Migration22to23Test {

    private val dbName = "migration-test-22-23.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `22 to 23 adds the fuel columns empty and keeps every EV trip as it was`() {
        helper.createDatabase(dbName, 22).use { old ->
            old.execSQL(
                "INSERT INTO trips (start_ts, end_ts, distance_km, kwh_consumed, cost, source) " +
                    "VALUES (1000, 2000, 50.5, 9.1, 1.82, 'energydata')"
            )
            old.execSQL("INSERT INTO last_state (id, ts, energydata_available) VALUES (1, 5000, 1)")
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 23, true, AppModule.MIGRATION_22_23)

        migrated.query(
            "SELECT count(*), sum(cost), fuel_liters, hev_km, fuel_total_start, fuel_total_end, " +
                "mileage_hev_start, mileage_hev_end, fuel_cost, distance_km FROM trips"
        ).use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
            assertEquals(1.82, c.getDouble(1), 1e-9)
            for (i in 2..8) assertTrue("column $i", c.isNull(i))
            assertEquals(50.5, c.getDouble(9), 1e-9)
        }
        migrated.query("SELECT trip_start_fuel_total, trip_start_mileage_hev, ts FROM last_state").use { c ->
            c.moveToFirst()
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
            assertEquals(5000L, c.getLong(2))
        }

        migrated.execSQL(
            "INSERT INTO counter_snapshots (ts, fuel_total, mileage_hev, mileage_ev, odometer, reason) " +
                "VALUES (3000, 302.4, 3812, 9240, 13052, 'ignition_on')"
        )
        migrated.query("SELECT fuel_total, reason FROM counter_snapshots").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(302.4, c.getDouble(0), 1e-9)
            assertEquals("ignition_on", c.getString(1))
        }
        migrated.close()
    }
}

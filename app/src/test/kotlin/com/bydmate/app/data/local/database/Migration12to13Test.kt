package com.bydmate.app.data.local.database

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModuleMigrationsForTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration12to13Test {

    private val dbName = "migration-12-13-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `migrate adds fuel and split cost columns to trips`() {
        helper.createDatabase(dbName, 12).apply {
            execSQL("""
                INSERT INTO trips (start_ts, end_ts, distance_km, kwh_consumed, kwh_per_100km, cost, source)
                VALUES (1700000000000, 1700003600000, 100.0, 18.0, 18.0, 72.0, 'diplus')
            """)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            13,
            true,
            AppModuleMigrationsForTest.MIGRATION_12_13
        )

        migrated.query(
            """
            SELECT distance_km, kwh_consumed, cost, fuel_liters, fuel_l_per_100km, electricity_cost, fuel_cost
            FROM trips
            """.trimIndent()
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(100.0, c.getDouble(0), 0.001)
            assertEquals(18.0, c.getDouble(1), 0.001)
            assertEquals(72.0, c.getDouble(2), 0.001)
            assertEquals(true, c.isNull(3))
            assertEquals(true, c.isNull(4))
            assertEquals(true, c.isNull(5))
            assertEquals(true, c.isNull(6))
        }

        val cv = ContentValues().apply {
            put("start_ts", 1700004000000L)
            put("distance_km", 50.0)
            put("kwh_consumed", 4.0)
            put("fuel_liters", 2.5)
            put("fuel_l_per_100km", 5.0)
            put("electricity_cost", 16.0)
            put("fuel_cost", 140.0)
            put("cost", 156.0)
            put("source", "diplus")
        }
        migrated.insert("trips", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, cv)

        migrated.query("SELECT fuel_liters, fuel_l_per_100km, electricity_cost, fuel_cost, cost FROM trips WHERE start_ts = 1700004000000").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(2.5, c.getDouble(0), 0.001)
            assertEquals(5.0, c.getDouble(1), 0.001)
            assertEquals(16.0, c.getDouble(2), 0.001)
            assertEquals(140.0, c.getDouble(3), 0.001)
            assertEquals(156.0, c.getDouble(4), 0.001)
        }
        migrated.close()
    }
}

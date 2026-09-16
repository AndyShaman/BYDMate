package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration19to20Test {

    private val dbName = "migration-test-19-20.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test fun `19 to 20 seeds one period from the flat settings and keeps every cost`() {
        helper.createDatabase(dbName, 19).use { old ->
            old.execSQL("INSERT INTO settings (key, value) VALUES ('home_tariff', '0,37')")
            old.execSQL("INSERT INTO settings (key, value) VALUES ('dc_tariff', '1.20')")
            old.execSQL("INSERT INTO settings (key, value) VALUES ('trip_cost_tariff', 'dc')")
            old.execSQL("""
                INSERT INTO charges (start_ts, kwh_charged, type, cost, status, merged_count)
                VALUES (1000, 10.0, 'AC', 3.70, 'COMPLETED', 0)
            """.trimIndent())
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 20, true, AppModule.MIGRATION_19_20)

        // The comma in "0,37" is the Russian numeric keyboard; it must not become 0.
        migrated.query("SELECT start_ts, home_rate, dc_rate, ac_loss_pct, dc_loss_pct, trip_rule FROM tariff_periods").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(0L, c.getLong(0))
            assertEquals(0.37, c.getDouble(1), 1e-9)
            assertEquals(1.20, c.getDouble(2), 1e-9)
            assertEquals(10.0, c.getDouble(3), 1e-9)
            assertEquals(5.0, c.getDouble(4), 1e-9)
            assertEquals("dc", c.getString(5))
        }

        // The migration re-prices nothing: the cost recorded before the upgrade survives it.
        migrated.query("SELECT cost, meter_kwh, cost_manual FROM charges").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(3.70, c.getDouble(0), 1e-9)
            assertTrue(c.isNull(1))
            assertEquals(0, c.getInt(2))
        }
        migrated.close()
    }

    @Test fun `empty tariff settings fall back to the defaults`() {
        helper.createDatabase(dbName, 19).use { old ->
            old.execSQL("INSERT INTO settings (key, value) VALUES ('home_tariff', '')")
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 20, true, AppModule.MIGRATION_19_20)

        migrated.query("SELECT home_rate, dc_rate, trip_rule FROM tariff_periods").use { c ->
            c.moveToFirst()
            assertEquals(0.20, c.getDouble(0), 1e-9)
            assertEquals(0.73, c.getDouble(1), 1e-9)
            assertEquals("home", c.getString(2))
        }
        migrated.close()
    }

    // Free charging at work is a real tariff of 0; only a blank or non-numeric setting may
    // fall back to the default.
    @Test fun `a tariff of zero survives the migration`() {
        helper.createDatabase(dbName, 19).use { old ->
            old.execSQL("INSERT INTO settings (key, value) VALUES ('home_tariff', '0')")
            old.execSQL("INSERT INTO settings (key, value) VALUES ('dc_tariff', '0.0')")
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 20, true, AppModule.MIGRATION_19_20)

        migrated.query("SELECT home_rate, dc_rate FROM tariff_periods").use { c ->
            c.moveToFirst()
            assertEquals(0.0, c.getDouble(0), 1e-9)
            assertEquals(0.0, c.getDouble(1), 1e-9)
        }
        migrated.close()
    }

    @Test fun `a non-numeric tariff falls back to the default`() {
        helper.createDatabase(dbName, 19).use { old ->
            old.execSQL("INSERT INTO settings (key, value) VALUES ('home_tariff', 'abc')")
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 20, true, AppModule.MIGRATION_19_20)

        migrated.query("SELECT home_rate, dc_rate FROM tariff_periods").use { c ->
            c.moveToFirst()
            assertEquals(0.20, c.getDouble(0), 1e-9)
            assertEquals(0.73, c.getDouble(1), 1e-9)
        }
        migrated.close()
    }

    @Test fun `the new charge columns accept values after the migration`() {
        helper.createDatabase(dbName, 19).close()
        val migrated = helper.runMigrationsAndValidate(dbName, 20, true, AppModule.MIGRATION_19_20)
        migrated.execSQL("""
            INSERT INTO charges (start_ts, kwh_charged, type, cost, status, merged_count, meter_kwh, cost_manual)
            VALUES (2000, 9.0, 'AC', 2.4, 'COMPLETED', 0, 12.0, 1)
        """.trimIndent())
        migrated.query("SELECT meter_kwh, cost_manual FROM charges").use { c ->
            c.moveToFirst()
            assertEquals(12.0, c.getDouble(0), 1e-9)
            assertFalse(c.getInt(1) == 0)
        }
        migrated.close()
    }
}

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
class Migration20to21Test {

    private val dbName = "migration-test-20-21.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `20 to 21 adds the odometer at trip start and finish`() {
        helper.createDatabase(dbName, 20).use { old ->
            old.execSQL("INSERT INTO trips (start_ts, end_ts, distance_km) VALUES (1000, 2000, 50.5)")
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 21, true,
            AppModule.MIGRATION_20_21,
        )

        // The trips written before the migration survive it with the odometer unknown.
        migrated.query("SELECT distance_km, odometer_start_km, odometer_end_km FROM trips").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(50.5, c.getDouble(0), 1e-9)
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }
        migrated.close()
    }
}

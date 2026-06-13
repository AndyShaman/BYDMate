package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModuleMigrationsForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration15to16Test {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `15 to 16 adds total_elec columns and preserves the last_state row`() {
        helper.createDatabase(dbName, 15).apply {
            execSQL(
                """
                INSERT INTO last_state (id, ts, soc, mileage, energydata_available)
                VALUES (1, 1700000000000, 80, 12345.6, 0)
                """
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 16, true,
            AppModuleMigrationsForTest.MIGRATION_15_16,
        )

        migrated.query("SELECT soc, total_elec, trip_start_total_elec FROM last_state").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(80, c.getInt(0))
            assertTrue(c.isNull(1))  // new columns default to NULL on upgrade
            assertTrue(c.isNull(2))
        }
        migrated.close()
    }
}

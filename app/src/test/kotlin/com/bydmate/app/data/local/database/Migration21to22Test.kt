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
class Migration21to22Test {

    private val dbName = "migration-test-21-22.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `21 to 22 adds the outside temperature of the open trip`() {
        helper.createDatabase(dbName, 21).use { old ->
            old.execSQL(
                "INSERT INTO last_state (id, ts, open_trip_id, trip_start_ts, trip_start_soc, energydata_available) " +
                    "VALUES (1, 2000, 1000, 1000, 80, 0)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 22, true,
            AppModule.MIGRATION_21_22,
        )

        // A trip left open by the previous version survives with the temperature unknown.
        migrated.query("SELECT open_trip_id, trip_start_soc, trip_start_exterior_temp FROM last_state").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1000L, c.getLong(0))
            assertEquals(80, c.getInt(1))
            assertTrue(c.isNull(2))
        }

        migrated.execSQL("UPDATE last_state SET trip_start_exterior_temp = -12")
        migrated.query("SELECT trip_start_exterior_temp FROM last_state").use { c ->
            c.moveToFirst()
            assertEquals(-12, c.getInt(0))
        }
        migrated.close()
    }
}

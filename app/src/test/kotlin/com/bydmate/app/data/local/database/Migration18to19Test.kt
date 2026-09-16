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
class Migration18to19Test {

    private val dbName = "migration-test-18-19.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `18 to 19 adds the finish temperature and the point altitude`() {
        helper.createDatabase(dbName, 18).use { old ->
            old.execSQL("INSERT INTO trips (start_ts, end_ts, exterior_temp) VALUES (1000, 2000, -7)")
            old.execSQL("INSERT INTO trip_points (trip_id, timestamp, lat, lon) VALUES (1, 1500, 53.9, 27.5)")
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 19, true,
            AppModule.MIGRATION_18_19,
        )

        // The rows written before the migration survive it with the new columns empty.
        migrated.query("SELECT exterior_temp, exterior_temp_end FROM trips").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(-7, c.getInt(0))
            assertTrue(c.isNull(1))
        }
        migrated.query("SELECT altitude FROM trip_points").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertTrue(c.isNull(0))
        }

        migrated.execSQL("UPDATE trips SET exterior_temp_end = -3")
        migrated.execSQL("UPDATE trip_points SET altitude = 212.5")
        migrated.query("SELECT t.exterior_temp_end, p.altitude FROM trips t, trip_points p").use { c ->
            c.moveToFirst()
            assertEquals(-3, c.getInt(0))
            assertEquals(212.5, c.getDouble(1), 1e-9)
        }
        migrated.close()
    }
}

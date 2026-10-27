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
class Migration23to24Test {

    private val dbName = "migration-test-23-24.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `23 to 24 keeps every tombstone and adds an empty start time`() {
        helper.createDatabase(dbName, 23).use { old ->
            old.execSQL("INSERT INTO trip_tombstones (byd_id) VALUES (42)")
            old.execSQL("INSERT INTO trip_tombstones (byd_id) VALUES (43)")
            old.execSQL("INSERT INTO trips (start_ts, source, byd_id) VALUES (1000, 'energydata', 44)")
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 24, true, AppModule.MIGRATION_23_24)

        migrated.query("SELECT byd_id, start_ts FROM trip_tombstones ORDER BY byd_id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst(); assertEquals(42L, c.getLong(0)); assertTrue(c.isNull(1))
            c.moveToNext(); assertEquals(43L, c.getLong(0)); assertTrue(c.isNull(1))
        }
        migrated.query("SELECT count(*) FROM trips").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        migrated.close()
    }
}

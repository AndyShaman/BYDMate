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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration17to18Test {

    private val dbName = "migration-test-17-18.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `17 to 18 creates empty trip_tombstones table`() {
        helper.createDatabase(dbName, 17).close()

        val migrated = helper.runMigrationsAndValidate(
            dbName, 18, true,
            AppModuleMigrationsForTest.MIGRATION_17_18,
        )

        migrated.execSQL("INSERT INTO trip_tombstones (byd_id) VALUES (42)")
        migrated.query("SELECT byd_id FROM trip_tombstones").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(42L, c.getLong(0))
        }
        migrated.close()
    }
}

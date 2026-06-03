package com.bydmate.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration14To15Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate14To15() {
        helper.createDatabase(TEST_DB, 14).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, AppModule.MIGRATION_14_15)
        db.query("SELECT id, ts, actionName, dev, fid, requested, readback, status, error, validated FROM vehicle_write_log LIMIT 1").use { c ->
            assertNotNull(c)
        }
        db.close()
    }

    private companion object { const val TEST_DB = "migration-test.db" }
}

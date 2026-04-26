package com.bydmate.app.data.local.database

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration11to12Test {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `migrate adds 4 new columns to charges table`() {
        helper.createDatabase(dbName, 11).apply {
            execSQL("""
                INSERT INTO charges (start_ts, end_ts, soc_start, soc_end, kwh_charged, status, merged_count)
                VALUES (1700000000000, 1700003600000, 50, 80, 20.5, 'COMPLETED', 0)
            """)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 12, /*validateDroppedTables=*/true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_11_12
        )

        migrated.query(
            "SELECT id, kwh_charged, status, lifetime_kwh_at_start, lifetime_kwh_at_finish, gun_state, detection_source FROM charges"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(20.5, c.getDouble(1), 0.001)
            assertEquals("COMPLETED", c.getString(2))
            assertEquals(true, c.isNull(3))   // lifetime_kwh_at_start
            assertEquals(true, c.isNull(4))   // lifetime_kwh_at_finish
            assertEquals(true, c.isNull(5))   // gun_state
            assertEquals(true, c.isNull(6))   // detection_source
        }
        migrated.close()
    }

    @Test
    fun `migrate deletes SUSPENDED and ACTIVE sessions but keeps COMPLETED`() {
        helper.createDatabase(dbName, 11).apply {
            execSQL("""
                INSERT INTO charges (start_ts, status, merged_count) VALUES
                  (1700000000000, 'COMPLETED', 0),
                  (1700001000000, 'SUSPENDED', 0),
                  (1700002000000, 'ACTIVE', 0),
                  (1700003000000, 'COMPLETED', 0)
            """)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 12, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_11_12
        )

        migrated.query("SELECT status FROM charges ORDER BY start_ts").use { c ->
            val statuses = mutableListOf<String>()
            while (c.moveToNext()) statuses += c.getString(0)
            assertEquals(listOf("COMPLETED", "COMPLETED"), statuses)
        }
        migrated.close()
    }

    @Test
    fun `new charge can be inserted with autoservice fields after migration`() {
        helper.createDatabase(dbName, 11).close()
        val migrated = helper.runMigrationsAndValidate(
            dbName, 12, true,
            com.bydmate.app.di.AppModuleMigrationsForTest.MIGRATION_11_12
        )

        val cv = ContentValues().apply {
            put("start_ts", 1700004000000L)
            put("end_ts", 1700007600000L)
            put("soc_start", 30)
            put("soc_end", 80)
            put("kwh_charged", 36.5)
            put("status", "COMPLETED")
            put("merged_count", 0)
            put("lifetime_kwh_at_start", 600.0)
            put("lifetime_kwh_at_finish", 636.5)
            put("gun_state", 2)
            put("detection_source", "autoservice_catchup")
        }
        migrated.insert("charges", android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL, cv)

        migrated.query("SELECT lifetime_kwh_at_finish, gun_state, detection_source FROM charges WHERE start_ts = 1700004000000").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(636.5, c.getDouble(0), 0.001)
            assertEquals(2, c.getInt(1))
            assertEquals("autoservice_catchup", c.getString(2))
        }
        migrated.close()
    }
}

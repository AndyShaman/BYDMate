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
class Migration16to17Test {

    private val dbName = "migration-test-16-17.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `16 to 17 adds play_sound and rewrites notification kinds`() {
        helper.createDatabase(dbName, 16).apply {
            execSQL(
                """
                INSERT INTO automation_rules
                    (name, enabled, trigger_logic, triggers, actions, cooldown_seconds,
                     require_park, confirm_before_execute, fire_once_per_trip, trigger_count, created_at)
                VALUES
                    ('loud', 1, 'AND', '[]',
                     '[{"command":"","displayName":"n","kind":"notification_sound","payload":"{}"}]',
                     60, 0, 0, 0, 0, 0),
                    ('quiet', 1, 'AND', '[]',
                     '[{"command":"","displayName":"n","kind":"notification_silent","payload":"{}"}]',
                     60, 0, 0, 0, 0, 0),
                    ('other', 1, 'AND', '[]',
                     '[{"command":"cmd","displayName":"p","kind":"param"}]',
                     60, 0, 0, 0, 0, 0)
                """
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 17, true,
            AppModule.MIGRATION_16_17,
        )

        migrated.query("SELECT name, play_sound, actions FROM automation_rules ORDER BY id").use { c ->
            assertEquals(3, c.count)
            c.moveToFirst()
            assertEquals("loud", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertTrue(c.getString(2).contains("\"kind\":\"notification\""))
            assertFalse(c.getString(2).contains("notification_sound"))
            c.moveToNext()
            assertEquals("quiet", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertTrue(c.getString(2).contains("\"kind\":\"notification\""))
            assertFalse(c.getString(2).contains("notification_silent"))
            c.moveToNext()
            assertEquals("other", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertTrue(c.getString(2).contains("\"kind\":\"param\""))
        }
        migrated.close()
    }
}

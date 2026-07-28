package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `18 to 19 creates empty radio_stations table`() {
        helper.createDatabase(dbName, 18).close()

        val migrated = helper.runMigrationsAndValidate(
            dbName, 19, true,
            AppModule.MIGRATION_18_19,
        )

        migrated.execSQL(
            "INSERT INTO radio_stations (name, url, icon_url, created_at) " +
                "VALUES ('Nightwave', 'https://stream.example/nw.mp3', NULL, 1700000000000)"
        )
        migrated.query("SELECT name, url, icon_url, created_at FROM radio_stations").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Nightwave", c.getString(0))
            assertEquals("https://stream.example/nw.mp3", c.getString(1))
            assertNull(c.getString(2))
            assertEquals(1700000000000L, c.getLong(3))
        }

        // A station added from a directory carries the lighter stream the "Экономить трафик"
        // setting switches to; one typed by hand leaves the column null, as above.
        migrated.execSQL(
            "INSERT INTO radio_stations (name, url, icon_url, low_bitrate_url, created_at) " +
                "VALUES ('Record', 'https://x/rr96.aacp', NULL, 'https://x/rr32.aacp', 1700000000001)"
        )
        migrated.query(
            "SELECT low_bitrate_url FROM radio_stations WHERE name = 'Record'"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("https://x/rr32.aacp", c.getString(0))
        }
        migrated.close()
    }
}

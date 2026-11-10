package com.bydmate.app.data.local.database

import androidx.room.Room
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
class FullMigrationChainTest {

    private val dbName = "migration-chain.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /**
     * The oldest exported schema (5) opened by the very builder the app uses
     * ([AppModule.withMigrations]), like a v1.x-era user updating straight to today's build.
     * A migration left out of that list fails the open ("a migration from N to N+1 was required
     * but not found"), and Room checks the result against the current entities. The trip, the
     * charge and the tariff the user had come through with their numbers.
     */
    @Test
    fun `a version 5 database opens on the production migrations with the user's data intact`() {
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO trips (id, start_ts, end_ts, distance_km, kwh_consumed, kwh_per_100km, soc_start, soc_end, cost) " +
                    "VALUES (1, 1700000000000, 1700003600000, 42.5, 8.1, 19.06, 80, 69, 1.62)"
            )
            execSQL(
                "INSERT INTO charges (id, start_ts, end_ts, soc_start, soc_end, kwh_charged, type, cost, status, merged_count) " +
                    "VALUES (1, 1700010000000, 1700020000000, 20, 80, 43.7, 'AC', 8.74, 'COMPLETED', 1)"
            )
            execSQL("INSERT INTO settings (key, value) VALUES ('home_tariff', '0.25')")
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppModule.withMigrations(Room.databaseBuilder(context, AppDatabase::class.java, dbName)).build()
        try {
            val sql = db.openHelper.writableDatabase   // runs every migration and Room's schema check

            sql.query("SELECT distance_km, kwh_consumed, kwh_per_100km, soc_start, soc_end, cost FROM trips WHERE id = 1").use { c ->
                assertTrue("the trip is gone", c.moveToFirst())
                assertEquals(42.5, c.getDouble(0), 0.0)
                assertEquals(8.1, c.getDouble(1), 0.0)
                assertEquals(19.06, c.getDouble(2), 0.0)
                assertEquals(80, c.getInt(3))
                assertEquals(69, c.getInt(4))
                assertEquals(1.62, c.getDouble(5), 0.0)
            }
            sql.query("SELECT soc_start, soc_end, kwh_charged, type, cost FROM charges WHERE id = 1").use { c ->
                assertTrue("the charge is gone", c.moveToFirst())
                assertEquals(20, c.getInt(0))
                assertEquals(80, c.getInt(1))
                assertEquals(43.7, c.getDouble(2), 0.0)
                assertEquals("AC", c.getString(3))
                assertEquals(8.74, c.getDouble(4), 0.0)
            }
            sql.query("SELECT value FROM settings WHERE key = 'home_tariff'").use { c ->
                assertTrue("the home tariff is gone", c.moveToFirst())
                assertEquals("0.25", c.getString(0))
            }
        } finally {
            db.close()
        }
    }
}

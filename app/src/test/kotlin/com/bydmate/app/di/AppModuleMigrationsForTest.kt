package com.bydmate.app.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Re-exports MIGRATION_11_12 (private inside AppModule object) so that
 * Migration11to12Test can pass it to MigrationTestHelper. Only used in
 * unit tests; production code stays in AppModule.
 *
 * Keep in lockstep with AppModule.MIGRATION_11_12 — if the migration
 * SQL changes, update both. The duplication is intentional: making
 * AppModule.MIGRATION_11_12 internal would leak Hilt internals.
 */
object AppModuleMigrationsForTest {
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_start REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_finish REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN gun_state INTEGER")
            db.execSQL("ALTER TABLE charges ADD COLUMN detection_source TEXT")
            db.execSQL("DELETE FROM charges WHERE status IN ('SUSPENDED', 'ACTIVE')")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_byd_id ON trips(byd_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_points_timestamp ON trip_points(timestamp)")
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS last_state (
                    id INTEGER NOT NULL PRIMARY KEY,
                    ts INTEGER NOT NULL,
                    soc INTEGER,
                    mileage REAL,
                    ignition INTEGER,
                    open_trip_id INTEGER,
                    trip_start_ts INTEGER,
                    trip_start_soc INTEGER,
                    trip_start_mileage REAL,
                    energydata_available INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vehicle_write_log (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    actionName TEXT NOT NULL,
                    dev INTEGER NOT NULL,
                    fid INTEGER NOT NULL,
                    requested INTEGER NOT NULL,
                    readback INTEGER,
                    status INTEGER NOT NULL,
                    error TEXT,
                    validated INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }
}

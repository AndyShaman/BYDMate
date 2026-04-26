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
}

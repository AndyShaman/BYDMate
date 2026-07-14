package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FullMigrationChainTest {

    private val dbName = "migration-chain-5-18.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /** Oldest exported schema (5) through current (18) on PRODUCTION migrations only —
     *  validates every step against the exported schema JSONs, like a v1.x-era user
     *  updating straight to today's build. */
    @Test
    fun `full chain 5 to 18 on production migrations`() {
        helper.createDatabase(dbName, 5).close()
        helper.runMigrationsAndValidate(
            dbName, 18, true,
            AppModule.MIGRATION_5_6, AppModule.MIGRATION_6_7, AppModule.MIGRATION_7_8,
            AppModule.MIGRATION_8_9, AppModule.MIGRATION_9_10, AppModule.MIGRATION_10_11,
            AppModule.MIGRATION_11_12, AppModule.MIGRATION_12_13, AppModule.MIGRATION_13_14,
            AppModule.MIGRATION_14_15, AppModule.MIGRATION_15_16, AppModule.MIGRATION_16_17,
            AppModule.MIGRATION_17_18,
        ).close()
    }
}

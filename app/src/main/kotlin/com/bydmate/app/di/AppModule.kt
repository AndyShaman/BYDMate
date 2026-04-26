package com.bydmate.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.OdometerSampleDao
import com.bydmate.app.data.local.dao.PlaceDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.domain.calculator.OdometerConsumptionBuffer
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.domain.calculator.SocInterpolator
import com.bydmate.app.domain.calculator.SocInterpolatorPrefs
import com.bydmate.app.domain.calculator.SocInterpolatorPrefsImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS idle_drains (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    start_ts INTEGER NOT NULL,
                    end_ts INTEGER,
                    soc_start INTEGER,
                    soc_end INTEGER,
                    kwh_consumed REAL
                )
            """)
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // TripEntity: battery temp + cost
            db.execSQL("ALTER TABLE trips ADD COLUMN bat_temp_avg REAL")
            db.execSQL("ALTER TABLE trips ADD COLUMN bat_temp_max REAL")
            db.execSQL("ALTER TABLE trips ADD COLUMN bat_temp_min REAL")
            db.execSQL("ALTER TABLE trips ADD COLUMN cost REAL")
            // ChargeEntity: battery temp + avg power
            db.execSQL("ALTER TABLE charges ADD COLUMN bat_temp_avg REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN bat_temp_max REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN bat_temp_min REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN avg_power_kw REAL")
            // ChargePointEntity: battery temp per sample
            db.execSQL("ALTER TABLE charge_points ADD COLUMN bat_temp INTEGER")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ChargeEntity: session status, cell voltages, 12V, ext temp, merge count
            db.execSQL("ALTER TABLE charges ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED'")
            db.execSQL("ALTER TABLE charges ADD COLUMN cell_voltage_min REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN cell_voltage_max REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN voltage_12v REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN exterior_temp INTEGER")
            db.execSQL("ALTER TABLE charges ADD COLUMN merged_count INTEGER NOT NULL DEFAULT 0")
            // TripEntity: exterior temp
            db.execSQL("ALTER TABLE trips ADD COLUMN exterior_temp INTEGER")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Indices for faster queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_start_ts ON trips(start_ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_charges_start_ts ON charges(start_ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_charges_status ON charges(status)")
            // Battery degradation tracking table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS battery_snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    odometer_km REAL,
                    soc_start INTEGER NOT NULL,
                    soc_end INTEGER NOT NULL,
                    kwh_charged REAL NOT NULL,
                    calculated_capacity_kwh REAL,
                    soh_percent REAL,
                    cell_delta_v REAL,
                    bat_temp_avg REAL,
                    charge_id INTEGER
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_battery_snapshots_timestamp ON battery_snapshots(timestamp)")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE trips ADD COLUMN source TEXT NOT NULL DEFAULT 'live'")
            db.execSQL("ALTER TABLE trips ADD COLUMN byd_id INTEGER")
        }
    }

    // Remove FOREIGN KEY from trip_points — tripId=0 (GPS before trip match) caused
    // SQLITE_CONSTRAINT_FOREIGNKEY (code 787), losing all GPS points
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS trip_points_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    trip_id INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    speed_kmh REAL
                )
            """)
            db.execSQL("INSERT INTO trip_points_new SELECT * FROM trip_points")
            db.execSQL("DROP TABLE trip_points")
            db.execSQL("ALTER TABLE trip_points_new RENAME TO trip_points")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_points_trip_id ON trip_points(trip_id)")
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS automation_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    trigger_logic TEXT NOT NULL DEFAULT 'AND',
                    triggers TEXT NOT NULL,
                    actions TEXT NOT NULL,
                    cooldown_seconds INTEGER NOT NULL DEFAULT 60,
                    require_park INTEGER NOT NULL DEFAULT 0,
                    confirm_before_execute INTEGER NOT NULL DEFAULT 0,
                    last_triggered_at INTEGER,
                    trigger_count INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
            """)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS automation_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    rule_id INTEGER NOT NULL,
                    rule_name TEXT NOT NULL,
                    triggered_at INTEGER NOT NULL,
                    triggers_snapshot TEXT NOT NULL,
                    actions_result TEXT NOT NULL,
                    success INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_log_triggered_at ON automation_log(triggered_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_log_rule_id ON automation_log(rule_id)")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    radius_m INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
            """)
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE automation_rules ADD COLUMN fire_once_per_trip INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS odometer_samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    mileage_km REAL NOT NULL,
                    total_elec_kwh REAL,
                    soc_percent INTEGER,
                    session_id INTEGER,
                    timestamp INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_odometer_samples_mileage_km ON odometer_samples(mileage_km)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_odometer_samples_session_id ON odometer_samples(session_id)")
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v12: autoservice catch-up trace + baseline source.
            // 4 new fields on existing 'charges' table — see spec section 5.1.
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_start REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_finish REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN gun_state INTEGER")
            db.execSQL("ALTER TABLE charges ADD COLUMN detection_source TEXT")

            // One-shot cleanup of unfinished sessions left by the removed
            // ChargeTracker. COMPLETED sessions stay in history.
            db.execSQL("DELETE FROM charges WHERE status IN ('SUSPENDED', 'ACTIVE')")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "bydmate.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()
    }

    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
    @Provides fun provideTripPointDao(db: AppDatabase): TripPointDao = db.tripPointDao()
    @Provides fun provideChargeDao(db: AppDatabase): ChargeDao = db.chargeDao()
    @Provides fun provideChargePointDao(db: AppDatabase): ChargePointDao = db.chargePointDao()
    @Provides fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()
    @Provides fun provideIdleDrainDao(db: AppDatabase): IdleDrainDao = db.idleDrainDao()
    @Provides fun provideBatterySnapshotDao(db: AppDatabase): BatterySnapshotDao = db.batterySnapshotDao()
    @Provides fun provideRuleDao(db: AppDatabase): RuleDao = db.ruleDao()
    @Provides fun provideRuleLogDao(db: AppDatabase): RuleLogDao = db.ruleLogDao()
    @Provides fun providePlaceDao(db: AppDatabase): PlaceDao = db.placeDao()
    @Provides fun provideOdometerSampleDao(db: AppDatabase): OdometerSampleDao = db.odometerSampleDao()

    @Provides
    @Singleton
    fun provideOdometerConsumptionBuffer(
        dao: OdometerSampleDao,
        tripRepository: com.bydmate.app.data.repository.TripRepository,
    ): OdometerConsumptionBuffer = OdometerConsumptionBuffer(
        dao = dao,
        fallbackEmaProvider = { tripRepository.getEmaConsumption() },
    )

    @Provides
    @Singleton
    fun provideSocInterpolatorPrefs(@ApplicationContext ctx: Context): SocInterpolatorPrefs =
        SocInterpolatorPrefsImpl(ctx)

    @Provides
    @Singleton
    fun provideSocInterpolator(
        prefs: SocInterpolatorPrefs,
    ): SocInterpolator = SocInterpolator(persistence = prefs)

    @Provides
    @Singleton
    fun provideRangeCalculator(
        buffer: OdometerConsumptionBuffer,
        settingsRepository: com.bydmate.app.data.repository.SettingsRepository,
        socInterpolator: SocInterpolator,
    ): RangeCalculator = RangeCalculator(
        buffer = buffer,
        capacityProvider = { settingsRepository.getBatteryCapacity() },
        socInterpolator = socInterpolator,
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideAdbOnDeviceClient(
        @ApplicationContext context: Context,
        keyStore: com.bydmate.app.data.autoservice.AdbKeyStore
    ): com.bydmate.app.data.autoservice.AdbOnDeviceClient =
        com.bydmate.app.data.autoservice.AdbOnDeviceClientImpl(context, keyStore)

    @Provides
    @Singleton
    fun provideAutoserviceClient(
        adb: com.bydmate.app.data.autoservice.AdbOnDeviceClient
    ): com.bydmate.app.data.autoservice.AutoserviceClient =
        com.bydmate.app.data.autoservice.AutoserviceClientImpl(adb)
}

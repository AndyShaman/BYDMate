package com.bydmate.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.database.AppDatabase
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "bydmate.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
    @Provides fun provideTripPointDao(db: AppDatabase): TripPointDao = db.tripPointDao()
    @Provides fun provideChargeDao(db: AppDatabase): ChargeDao = db.chargeDao()
    @Provides fun provideChargePointDao(db: AppDatabase): ChargePointDao = db.chargePointDao()
    @Provides fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()
    @Provides fun provideIdleDrainDao(db: AppDatabase): IdleDrainDao = db.idleDrainDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

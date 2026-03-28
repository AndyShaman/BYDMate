package com.bydmate.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity

@Database(
    entities = [
        TripEntity::class,
        TripPointEntity::class,
        ChargeEntity::class,
        ChargePointEntity::class,
        SettingEntity::class,
        IdleDrainEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun tripPointDao(): TripPointDao
    abstract fun chargeDao(): ChargeDao
    abstract fun chargePointDao(): ChargePointDao
    abstract fun settingsDao(): SettingsDao
    abstract fun idleDrainDao(): IdleDrainDao
}

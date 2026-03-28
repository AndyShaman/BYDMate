package com.bydmate.app.data.local

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class BydTripRecord(
    val id: Long,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val duration: Long,       // seconds
    val tripKm: Double,       // distance
    val electricityKwh: Double // BYD's own estimate
)

@Singleton
class EnergyDataReader @Inject constructor() {

    companion object {
        private const val DB_PATH = "/storage/emulated/0/energydata"
    }

    suspend fun readTrips(): List<BydTripRecord> = withContext(Dispatchers.IO) {
        try {
            val db = SQLiteDatabase.openDatabase(DB_PATH, null, SQLiteDatabase.OPEN_READONLY)
            db.use { database ->
                val cursor = database.rawQuery(
                    "SELECT id, start_timestamp, end_timestamp, duration, trip, electricity " +
                        "FROM EnergyConsumption WHERE is_deleted = 0 ORDER BY start_timestamp DESC",
                    null
                )
                val results = mutableListOf<BydTripRecord>()
                cursor.use {
                    while (it.moveToNext()) {
                        results.add(
                            BydTripRecord(
                                id = it.getLong(0),
                                startTimestamp = it.getLong(1),
                                endTimestamp = it.getLong(2),
                                duration = it.getLong(3),
                                tripKm = it.getDouble(4),
                                electricityKwh = it.getDouble(5)
                            )
                        )
                    }
                }
                results
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

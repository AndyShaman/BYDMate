package com.bydmate.app.data.remote

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DiPlusTripRecord(
    val timeStart: Long,       // epoch milliseconds
    val timeEnd: Long,         // epoch milliseconds
    val mileage: Double,       // km
    val travelTime: Double,    // seconds
    val avgSpeed: Double,      // km/h
    val socStart: Double,      // %
    val socEnd: Double,        // %
    val kwhConsumed: Double,   // elecCon_end - elecCon_start
    val odometerStart: Double, // km (mileage_start / 10)
    val odometerEnd: Double    // km (mileage_end / 10)
)

@Singleton
class DiPlusDbReader @Inject constructor() {
    companion object {
        private const val TAG = "DiPlusDbReader"
        private const val DIPLUS_DB_PATH = "/storage/emulated/0/vandiplus/db/van_bm_db"
    }

    suspend fun readTripInfo(): List<DiPlusTripRecord> = withContext(Dispatchers.IO) {
        val dbFile = File(DIPLUS_DB_PATH)
        if (!dbFile.exists()) {
            Log.w(TAG, "DiPlus DB not found: $DIPLUS_DB_PATH")
            return@withContext emptyList()
        }

        val results = mutableListOf<DiPlusTripRecord>()
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // Check if TripInfo table exists
            val tableCursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='TripInfo'", null
            )
            if (!tableCursor.moveToFirst()) {
                tableCursor.close()
                Log.w(TAG, "TripInfo table not found")
                return@withContext emptyList()
            }
            tableCursor.close()

            val cursor = db.rawQuery(
                """SELECT time_start, time_end, mileage, travelTime, avgSpeed,
                          elecPer_start, elecPer_end, elecCon_start, elecCon_end,
                          mileage_start, mileage_end
                   FROM TripInfo ORDER BY time_start""", null
            )
            while (cursor.moveToNext()) {
                val timeStart = cursor.getLong(0)   // milliseconds
                val timeEnd = cursor.getLong(1)     // milliseconds
                val mileage = cursor.getDouble(2)
                val travelTime = cursor.getDouble(3)
                val avgSpeed = cursor.getDouble(4)
                val socStart = cursor.getDouble(5)
                val socEnd = cursor.getDouble(6)
                val elecConStart = cursor.getDouble(7)
                val elecConEnd = cursor.getDouble(8)
                val mileageStart = cursor.getDouble(9) / 10.0
                val mileageEnd = cursor.getDouble(10) / 10.0

                results.add(DiPlusTripRecord(
                    timeStart = timeStart,
                    timeEnd = timeEnd,
                    mileage = mileage,
                    travelTime = travelTime,
                    avgSpeed = avgSpeed,
                    socStart = socStart,
                    socEnd = socEnd,
                    kwhConsumed = elecConEnd - elecConStart,
                    odometerStart = mileageStart,
                    odometerEnd = mileageEnd
                ))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "readTripInfo failed", e)
        } finally {
            db.close()
        }

        Log.d(TAG, "readTripInfo: ${results.size} records")
        results
    }

    fun findMatchingTrip(
        diplusTrips: List<DiPlusTripRecord>,
        startTs: Long,
        endTs: Long
    ): DiPlusTripRecord? {
        return diplusTrips.firstOrNull { diplus ->
            kotlin.math.abs(diplus.timeEnd - endTs) < 120_000L
        }
    }
}

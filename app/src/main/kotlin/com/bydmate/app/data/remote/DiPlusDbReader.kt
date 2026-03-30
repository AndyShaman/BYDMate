package com.bydmate.app.data.remote

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
class DiPlusDbReader @Inject constructor(
    private val chargeRepository: ChargeRepository,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "DiPlusDbReader"
        private const val DIPLUS_DB_PATH = "/storage/emulated/0/vandiplus/db/van_bm_db"
    }

    data class ImportResult(
        val imported: Int = 0,
        val skipped: Int = 0,
        val totalInDb: Int = 0,
        val error: String? = null
    ) {
        val isError: Boolean get() = error != null
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

    suspend fun importChargingLog(): ImportResult = withContext(Dispatchers.IO) {
        try {
            doImport()
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportResult(error = e.message ?: e.toString())
        }
    }

    private suspend fun doImport(): ImportResult {
        val dbFile = File(DIPLUS_DB_PATH)
        if (!dbFile.exists()) {
            return ImportResult(error = "База DiPlus не найдена: $DIPLUS_DB_PATH")
        }

        val batteryCapacity = settingsRepository.getBatteryCapacity()

        // Load existing charges to skip duplicates by start timestamp
        val existingCharges = chargeRepository.getAllCharges().first()
        val existingStartTs = existingCharges.map { it.startTs }.toHashSet()

        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        var imported = 0
        var skipped = 0
        var rowCount = 0

        try {
            // Check if ChargingLog table exists
            val tableCursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='ChargingLog'", null
            )
            if (!tableCursor.moveToFirst()) {
                tableCursor.close()
                db.close()
                return ImportResult(error = "Таблица ChargingLog не найдена в базе DiPlus")
            }
            tableCursor.close()

            // Log all tables and ChargingLog row count for debugging
            val tablesCursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", null
            )
            val tables = mutableListOf<String>()
            while (tablesCursor.moveToNext()) { tables.add(tablesCursor.getString(0)) }
            tablesCursor.close()
            Log.d(TAG, "DiPlus DB tables: $tables")

            val countCursor = db.rawQuery("SELECT COUNT(*) FROM ChargingLog", null)
            countCursor.moveToFirst()
            rowCount = countCursor.getInt(0)
            countCursor.close()
            Log.d(TAG, "ChargingLog rows: $rowCount")

            if (rowCount == 0) {
                db.close()
                return ImportResult(error = "Таблица ChargingLog пуста (0 записей)")
            }

            // Log first row for debugging
            val sampleCursor = db.rawQuery(
                "SELECT * FROM ChargingLog LIMIT 1", null
            )
            if (sampleCursor.moveToFirst()) {
                val cols = sampleCursor.columnNames.joinToString(", ")
                val vals = (0 until sampleCursor.columnCount).joinToString(", ") {
                    sampleCursor.getString(it) ?: "null"
                }
                Log.d(TAG, "ChargingLog columns: $cols")
                Log.d(TAG, "ChargingLog sample: $vals")
            }
            sampleCursor.close()

            val cursor = db.rawQuery(
                """SELECT elecPer_start, elecPer_end, duration, chargingType,
                          time, preTime
                   FROM ChargingLog ORDER BY time DESC""", null
            )

            while (cursor.moveToNext()) {
                val socStart = cursor.getDouble(0).toInt()
                val socEnd = cursor.getDouble(1).toInt()
                val durationSec = cursor.getLong(2)
                val chargingType = cursor.getInt(3)
                val endTime = cursor.getLong(4)   // time = record time (end of charge)
                val preTime = cursor.getLong(5)   // preTime = start of charge

                // Use preTime as start; fall back to endTime - duration
                val startTime = if (preTime > 0) preTime else endTime - durationSec

                // DiPlus stores timestamps in seconds
                val startTsMs = startTime * 1000L
                val endTsMs = endTime * 1000L

                // Skip duplicates (within 60s tolerance)
                val isDuplicate = existingStartTs.any {
                    kotlin.math.abs(it - startTsMs) < 60_000L
                }
                if (isDuplicate) {
                    skipped++
                    continue
                }

                // Calculate kWh from SOC delta
                val kwhBySoc = if (socEnd > socStart) {
                    (socEnd - socStart) / 100.0 * batteryCapacity
                } else null

                val type = if (chargingType == 2) "DC" else "AC"

                // Calculate cost
                val tariff = if (type == "DC") {
                    settingsRepository.getDcTariff()
                } else {
                    settingsRepository.getHomeTariff()
                }
                val cost = kwhBySoc?.let { it * tariff }

                chargeRepository.insertCharge(
                    ChargeEntity(
                        startTs = startTsMs,
                        endTs = endTsMs,
                        socStart = socStart,
                        socEnd = socEnd,
                        kwhChargedSoc = kwhBySoc,
                        kwhCharged = kwhBySoc,
                        type = type,
                        cost = cost,
                        status = "COMPLETED"
                    )
                )

                existingStartTs.add(startTsMs)
                imported++
            }
            cursor.close()
        } finally {
            db.close()
        }

        Log.d(TAG, "Import done: $imported imported, $skipped skipped, $rowCount total in DB")
        return ImportResult(imported = imported, skipped = skipped, totalInDb = rowCount)
    }
}

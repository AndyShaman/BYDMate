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

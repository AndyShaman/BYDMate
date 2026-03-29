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

            val cursor = db.rawQuery(
                """SELECT elecPer_start, elecPer_end, duration, chargingType,
                          startTime, endTime
                   FROM ChargingLog ORDER BY startTime DESC""", null
            )

            while (cursor.moveToNext()) {
                val socStart = cursor.getInt(0)
                val socEnd = cursor.getInt(1)
                val durationSec = cursor.getLong(2)
                val chargingType = cursor.getInt(3)
                val startTime = cursor.getLong(4)
                val endTime = cursor.getLong(5)

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

        Log.d(TAG, "Import done: $imported imported, $skipped skipped")
        return ImportResult(imported = imported, skipped = skipped)
    }
}

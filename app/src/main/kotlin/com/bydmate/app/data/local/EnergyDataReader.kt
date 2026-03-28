package com.bydmate.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

/**
 * Reads BYD trip history from the energydata directory.
 *
 * /storage/emulated/0/energydata is a DIRECTORY containing .db files.
 * We find the newest .db file, copy it to app-local storage (to avoid
 * locking BYD's database), then read EnergyConsumption table.
 */
@Singleton
class EnergyDataReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "EnergyDataReader"
        private const val ENERGY_DIR_PATH = "/storage/emulated/0/energydata"
        private const val LOCAL_DB_NAME = "vehicle_ec.db"
    }

    /**
     * Find newest .db file in energydata dir, copy locally, read trips.
     * Throws on error (caller decides how to handle).
     */
    suspend fun readTrips(): List<BydTripRecord> = withContext(Dispatchers.IO) {
        val energyDir = File(ENERGY_DIR_PATH)
        if (!energyDir.exists() || !energyDir.isDirectory) {
            Log.w(TAG, "energydata directory not found: $ENERGY_DIR_PATH")
            return@withContext emptyList()
        }

        // Find all .db/.sqlite files, pick the newest
        val dbFiles = energyDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".db", ignoreCase = true) ||
                file.name.endsWith(".sqlite", ignoreCase = true))
        }

        if (dbFiles.isNullOrEmpty()) {
            Log.w(TAG, "No .db/.sqlite files found in $ENERGY_DIR_PATH")
            return@withContext emptyList()
        }

        val newest = dbFiles.maxByOrNull { it.lastModified() }!!
        Log.d(TAG, "Using source DB: ${newest.absolutePath} (${newest.length()} bytes)")

        // Copy to app-local storage to avoid locking BYD's database
        val localDb = copyToLocal(newest)
        Log.d(TAG, "Local copy: ${localDb.absolutePath}")

        // Read trips from local copy
        readTripsFromDb(localDb)
    }

    private fun copyToLocal(source: File): File {
        val extDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("ExternalFilesDir not available")
        val localFile = File(extDir, LOCAL_DB_NAME)

        FileInputStream(source).use { input ->
            FileOutputStream(localFile).use { output ->
                input.copyTo(output)
            }
        }
        return localFile
    }

    private fun readTripsFromDb(dbFile: File): List<BydTripRecord> {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        return db.use { database ->
            val cursor = database.rawQuery(
                """SELECT _id, start_timestamp, end_timestamp, duration, trip, electricity
                   FROM EnergyConsumption
                   WHERE is_deleted = 0
                   ORDER BY start_timestamp DESC""",
                null
            )
            val results = mutableListOf<BydTripRecord>()
            cursor.use { c ->
                val colId = c.getColumnIndexOrThrow("_id")
                val colStart = c.getColumnIndexOrThrow("start_timestamp")
                val colEnd = c.getColumnIndexOrThrow("end_timestamp")
                val colDuration = c.getColumnIndexOrThrow("duration")
                val colTrip = c.getColumnIndexOrThrow("trip")
                val colElectricity = c.getColumnIndexOrThrow("electricity")

                while (c.moveToNext()) {
                    results.add(
                        BydTripRecord(
                            id = c.getLong(colId),
                            startTimestamp = c.getLong(colStart),
                            endTimestamp = c.getLong(colEnd),
                            duration = c.getLong(colDuration),
                            tripKm = c.getDouble(colTrip),
                            electricityKwh = c.getDouble(colElectricity)
                        )
                    )
                }
            }
            Log.d(TAG, "Read ${results.size} trips from EnergyConsumption")
            results
        }
    }
}

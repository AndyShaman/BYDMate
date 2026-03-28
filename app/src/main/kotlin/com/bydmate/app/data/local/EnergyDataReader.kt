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
 *
 * Throws descriptive exceptions when data is unavailable — callers
 * (HistoryImporter) catch and surface the message to the user.
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
     * Throws [EnergyDataException] when the directory or DB files are missing,
     * or when reading fails.
     */
    suspend fun readTrips(): List<BydTripRecord> = withContext(Dispatchers.IO) {
        Log.d(TAG, "readTrips() started, looking for: $ENERGY_DIR_PATH")

        val energyDir = File(ENERGY_DIR_PATH)
        if (!energyDir.exists() || !energyDir.isDirectory) {
            val msg = "energydata directory not found at $ENERGY_DIR_PATH. " +
                "Make sure READ_EXTERNAL_STORAGE permission is granted and " +
                "the BYD energy database exists on this device."
            Log.w(TAG, msg)
            throw EnergyDataException(msg)
        }
        Log.d(TAG, "energydata directory exists: ${energyDir.absolutePath}")

        // List everything in the directory for diagnostics
        val allFiles = energyDir.listFiles()
        val allNames = allFiles?.map { "${it.name} (${it.length()} bytes, dir=${it.isDirectory})" }
            ?: emptyList()
        Log.d(TAG, "Directory contents (${allNames.size} entries): $allNames")

        // Find all .db/.sqlite files, pick the newest
        val dbFiles = allFiles?.filter { file ->
            file.isFile && (file.name.endsWith(".db", ignoreCase = true) ||
                file.name.endsWith(".sqlite", ignoreCase = true))
        } ?: emptyList()

        if (dbFiles.isEmpty()) {
            val msg = "No .db/.sqlite files in $ENERGY_DIR_PATH. " +
                "Directory contains: ${allNames.joinToString(", ").ifEmpty { "(empty)" }}"
            Log.w(TAG, msg)
            throw EnergyDataException(msg)
        }
        Log.d(TAG, "Found ${dbFiles.size} DB file(s): ${dbFiles.map { it.name }}")

        val newest = dbFiles.maxByOrNull { it.lastModified() }!!
        Log.d(TAG, "Using source DB: ${newest.absolutePath} " +
            "(${newest.length()} bytes, modified=${newest.lastModified()})")

        // Copy to app-local storage to avoid locking BYD's database
        val localDb = copyToLocal(newest)
        Log.d(TAG, "Copied to local: ${localDb.absolutePath} (${localDb.length()} bytes)")

        // Read trips from local copy
        val trips = readTripsFromDb(localDb)
        Log.d(TAG, "readTrips() completed: ${trips.size} trips")
        trips
    }

    private fun copyToLocal(source: File): File {
        val extDir = context.getExternalFilesDir(null)
            ?: throw EnergyDataException("ExternalFilesDir not available — cannot copy DB locally")
        val localFile = File(extDir, LOCAL_DB_NAME)

        Log.d(TAG, "Copying ${source.absolutePath} -> ${localFile.absolutePath}")
        FileInputStream(source).use { input ->
            FileOutputStream(localFile).use { output ->
                input.copyTo(output)
            }
        }
        return localFile
    }

    private fun readTripsFromDb(dbFile: File): List<BydTripRecord> {
        Log.d(TAG, "Opening local DB: ${dbFile.absolutePath}")
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
                Log.d(TAG, "Cursor columns: ${(0 until c.columnCount).map { c.getColumnName(it) }}")
                Log.d(TAG, "Cursor row count: ${c.count}")

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

/** Thrown when BYD energy data is unavailable or unreadable. */
class EnergyDataException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

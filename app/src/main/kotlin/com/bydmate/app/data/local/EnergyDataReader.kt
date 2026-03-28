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
 * On DiLink, the main file is typically EC_database.db.
 *
 * Strategy:
 * 1. Try listFiles() to find .db files
 * 2. If listFiles() returns null (permission issue on Android 11+),
 *    try known filenames directly (EC_database.db)
 * 3. Copy to app-local storage to avoid locking BYD's database
 * 4. Read EnergyConsumption table from the local copy
 */
@Singleton
class EnergyDataReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "EnergyDataReader"
        private const val ENERGY_DIR_PATH = "/storage/emulated/0/energydata"
        private const val LOCAL_DB_NAME = "vehicle_ec.db"
        // Known BYD energy database filenames
        private val KNOWN_DB_NAMES = listOf(
            "EC_database.db",
            "energydata.db",
            "energy.db",
            "EnergyConsumption.db"
        )
    }

    /**
     * Run full diagnostics and return a human-readable report.
     * Shows directory access, DB files, table structure, row counts, sample data.
     */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("=== Хранилище BYD ===")

        val energyDir = File(ENERGY_DIR_PATH)
        sb.appendLine("Путь: $ENERGY_DIR_PATH")
        sb.appendLine("Существует: ${energyDir.exists()}")
        sb.appendLine("isDirectory: ${energyDir.isDirectory}")

        if (!energyDir.exists()) {
            sb.appendLine("ОШИБКА: директория не найдена!")
            return@withContext sb.toString()
        }

        // Try listFiles
        val allFiles = energyDir.listFiles()
        if (allFiles == null) {
            sb.appendLine("listFiles(): null (нет доступа)")
        } else {
            sb.appendLine("listFiles(): ${allFiles.size} файлов")
            allFiles.forEach { f ->
                sb.appendLine("  ${f.name} (${f.length()} bytes, ${if (f.isFile) "file" else "dir"})")
            }
        }

        // Try known names
        sb.appendLine("\nПроверка известных имён:")
        for (name in KNOWN_DB_NAMES) {
            val f = File(energyDir, name)
            val status = when {
                !f.exists() -> "не найден"
                f.length() == 0L -> "пустой"
                else -> "${f.length()} bytes, canRead=${f.canRead()}"
            }
            sb.appendLine("  $name: $status")
        }

        // Try to find and read the DB
        val sourceDb = findDbViaListFiles(energyDir) ?: findDbViaKnownNames(energyDir)
        if (sourceDb == null) {
            sb.appendLine("\nОШИБКА: не удалось найти БД!")
            return@withContext sb.toString()
        }

        sb.appendLine("\nИсточник: ${sourceDb.name} (${sourceDb.length()} bytes)")

        try {
            val localDb = copyToLocal(sourceDb)
            sb.appendLine("Копия: ${localDb.absolutePath} (${localDb.length()} bytes)")

            val db = SQLiteDatabase.openDatabase(
                localDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            db.use { database ->
                // Tables
                val tables = mutableListOf<String>()
                database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'", null
                ).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
                sb.appendLine("\n=== Таблицы в БД ===")
                sb.appendLine(tables.joinToString(", "))

                if (!tables.contains("EnergyConsumption")) {
                    sb.appendLine("ОШИБКА: таблица EnergyConsumption не найдена!")
                    return@withContext sb.toString()
                }

                // Columns
                database.rawQuery("PRAGMA table_info(EnergyConsumption)", null).use { c ->
                    sb.appendLine("\n=== Колонки EnergyConsumption ===")
                    while (c.moveToNext()) {
                        sb.appendLine("  ${c.getString(1)} (${c.getString(2)})")
                    }
                }

                // Counts
                val totalCount = database.rawQuery(
                    "SELECT COUNT(*) FROM EnergyConsumption", null
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                val activeCount = database.rawQuery(
                    "SELECT COUNT(*) FROM EnergyConsumption WHERE is_deleted = 0", null
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

                sb.appendLine("\n=== Строки ===")
                sb.appendLine("Всего: $totalCount")
                sb.appendLine("Активных (is_deleted=0): $activeCount")
                sb.appendLine("Удалённых: ${totalCount - activeCount}")

                // Sample data (first 5)
                sb.appendLine("\n=== Примеры данных (первые 5) ===")
                database.rawQuery(
                    """SELECT _id, start_timestamp, end_timestamp, duration, trip, electricity, is_deleted
                       FROM EnergyConsumption
                       ORDER BY start_timestamp DESC
                       LIMIT 5""", null
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val startTs = c.getLong(1)
                        val endTs = c.getLong(2)
                        val dur = c.getLong(3)
                        val km = c.getDouble(4)
                        val kwh = c.getDouble(5)
                        val deleted = c.getInt(6)
                        // Format timestamp for readability
                        val startFmt = try {
                            java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.US)
                                .format(java.util.Date(startTs * 1000L))
                        } catch (_: Exception) { "?" }
                        sb.appendLine("#$id: $startFmt, ${dur}s, %.1f km, %.2f kWh, del=$deleted".format(km, kwh))
                    }
                }
            }
        } catch (e: Exception) {
            sb.appendLine("ОШИБКА при чтении БД: ${e.message}")
        }

        sb.toString()
    }

    suspend fun readTrips(): List<BydTripRecord> = withContext(Dispatchers.IO) {
        Log.d(TAG, "readTrips() started, looking for: $ENERGY_DIR_PATH")

        val energyDir = File(ENERGY_DIR_PATH)
        if (!energyDir.exists()) {
            val msg = "Директория energydata не найдена: $ENERGY_DIR_PATH. " +
                "Убедитесь, что разрешение READ_EXTERNAL_STORAGE выдано."
            Log.w(TAG, msg)
            throw EnergyDataException(msg)
        }
        Log.d(TAG, "energydata directory exists: ${energyDir.absolutePath}")

        // Strategy 1: Try listFiles() to find .db files
        val sourceDb = findDbViaListFiles(energyDir)
            // Strategy 2: If listing failed, try known filenames directly
            ?: findDbViaKnownNames(energyDir)
            ?: throw EnergyDataException(
                "Не найдены файлы БД в $ENERGY_DIR_PATH. " +
                "listFiles() вернул null (нет доступа к директории). " +
                "Проверьте разрешения приложения."
            )

        Log.d(TAG, "Source DB: ${sourceDb.absolutePath} (${sourceDb.length()} bytes)")

        // Copy to app-local storage to avoid locking BYD's database
        val localDb = copyToLocal(sourceDb)
        Log.d(TAG, "Copied to local: ${localDb.absolutePath} (${localDb.length()} bytes)")

        val trips = readTripsFromDb(localDb)
        Log.d(TAG, "readTrips() completed: ${trips.size} trips")
        trips
    }

    /**
     * Try to find .db files via listFiles(). Returns newest .db file or null
     * if listFiles() returns null (common on Android 11+ without MANAGE_EXTERNAL_STORAGE).
     */
    private fun findDbViaListFiles(dir: File): File? {
        val allFiles = dir.listFiles()
        if (allFiles == null) {
            Log.w(TAG, "listFiles() returned null — no permission to list directory")
            return null
        }
        Log.d(TAG, "Directory has ${allFiles.size} files: ${allFiles.map { it.name }}")

        val dbFiles = allFiles.filter { file ->
            file.isFile && (file.name.endsWith(".db", ignoreCase = true) ||
                file.name.endsWith(".sqlite", ignoreCase = true))
        }

        if (dbFiles.isEmpty()) {
            Log.w(TAG, "No .db/.sqlite files found via listFiles()")
            return null
        }

        val newest = dbFiles.maxByOrNull { it.lastModified() }!!
        Log.d(TAG, "Found via listFiles(): ${newest.name}")
        return newest
    }

    /**
     * Fallback: try opening known BYD database filenames directly.
     * On Android 11+ with scoped storage, listFiles() fails but
     * opening a specific file by path may still work.
     */
    private fun findDbViaKnownNames(dir: File): File? {
        Log.d(TAG, "Trying known DB filenames as fallback...")
        for (name in KNOWN_DB_NAMES) {
            val file = File(dir, name)
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "Found via known name: $name (${file.length()} bytes)")
                return file
            } else {
                Log.d(TAG, "Tried $name — ${if (file.exists()) "exists but empty" else "not found"}")
            }
        }
        Log.w(TAG, "No known DB filenames found in ${dir.absolutePath}")
        return null
    }

    private fun copyToLocal(source: File): File {
        val extDir = context.getExternalFilesDir(null)
            ?: throw EnergyDataException("ExternalFilesDir не доступен")
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
            // Log all tables in the database for diagnostics
            val tablesCursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", null
            )
            val tables = mutableListOf<String>()
            tablesCursor.use { tc ->
                while (tc.moveToNext()) tables.add(tc.getString(0))
            }
            Log.d(TAG, "Tables in DB: $tables")

            if (!tables.contains("EnergyConsumption")) {
                Log.w(TAG, "EnergyConsumption table NOT FOUND in DB! Available tables: $tables")
                return@use emptyList()
            }

            // Count total rows vs non-deleted rows
            val totalCount = database.rawQuery(
                "SELECT COUNT(*) FROM EnergyConsumption", null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

            val activeCount = database.rawQuery(
                "SELECT COUNT(*) FROM EnergyConsumption WHERE is_deleted = 0", null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

            Log.d(TAG, "EnergyConsumption: total=$totalCount, active(is_deleted=0)=$activeCount")

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
                    val record = BydTripRecord(
                        id = c.getLong(colId),
                        startTimestamp = c.getLong(colStart),
                        endTimestamp = c.getLong(colEnd),
                        duration = c.getLong(colDuration),
                        tripKm = c.getDouble(colTrip),
                        electricityKwh = c.getDouble(colElectricity)
                    )
                    results.add(record)
                    // Log first 3 records for diagnostics
                    if (results.size <= 3) {
                        Log.d(TAG, "Sample record: id=${record.id}, " +
                            "start=${record.startTimestamp}, end=${record.endTimestamp}, " +
                            "dur=${record.duration}, km=${record.tripKm}, kwh=${record.electricityKwh}")
                    }
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

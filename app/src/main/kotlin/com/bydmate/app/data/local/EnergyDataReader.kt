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
open class EnergyDataReader @Inject constructor(
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

            // READWRITE on our private copy: read-only open cannot replay the -wal sidecar.
            val db = SQLiteDatabase.openDatabase(
                localDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
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

    /**
     * Read-only change detector. Does NOT mutate watermark — caller must invoke
     * [markSourceProcessed] only after a successful sync, so a failed import
     * does not silently skip the next event.
     */
    fun peekSourceChanged(context: Context): Boolean {
        val prefs = context.getSharedPreferences("energydata_sync", Context.MODE_PRIVATE)
        val dir = energyDataDir()
        if (!dir.exists()) return false

        val sourceDb = findDbViaListFiles(dir) ?: findDbViaKnownNames(dir) ?: return false
        // WAL-aware (AC-04): a live DB may keep new trips wal-only for a long time —
        // the main file alone can read as "unchanged". Same formula as sourceFingerprint().
        val wal = File(dir, sourceDb.name + "-wal")

        val storedModified = prefs.getLong("lastModified", 0L)
        val storedSize = prefs.getLong("fileSize", 0L)
        val storedPath = prefs.getString("filePath", "") ?: ""

        return (sourceDb.lastModified() + wal.lastModified()) != storedModified ||
            (sourceDb.length() + wal.length()) != storedSize ||
            sourceDb.absolutePath != storedPath
    }

    /** Persist current source-file fingerprint. Call only after successful import. */
    fun markSourceProcessed(context: Context) {
        val dir = energyDataDir()
        if (!dir.exists()) return
        val sourceDb = findDbViaListFiles(dir) ?: findDbViaKnownNames(dir) ?: return
        val wal = File(dir, sourceDb.name + "-wal")
        context.getSharedPreferences("energydata_sync", Context.MODE_PRIVATE).edit()
            .putLong("lastModified", sourceDb.lastModified() + wal.lastModified())
            .putLong("fileSize", sourceDb.length() + wal.length())
            .putString("filePath", sourceDb.absolutePath)
            .apply()
    }

    /** Force re-detection on next [peekSourceChanged]. Used by one-shot recovery flows. */
    fun resetWatermark(context: Context) {
        context.getSharedPreferences("energydata_sync", Context.MODE_PRIVATE).edit()
            .remove("lastModified")
            .remove("fileSize")
            .remove("filePath")
            .apply()
    }

    suspend fun readTripsSince(sinceTimestampSec: Long): List<BydTripRecord> = withContext(Dispatchers.IO) {
        val energyDir = energyDataDir()
        if (!energyDir.exists()) {
            Log.i(TAG, "readTripsSince($sinceTimestampSec): directory ${energyDir.path} missing — empty")
            return@withContext emptyList()
        }

        val sourceDb = findDbViaListFiles(energyDir)
            ?: findDbViaKnownNames(energyDir)
            ?: run {
                Log.i(TAG, "readTripsSince: no .db files in ${energyDir.path} (Song / non-Leopard?) — empty")
                return@withContext emptyList()
            }
        Log.i(TAG, "readTripsSince($sinceTimestampSec): source=${sourceDb.name} size=${sourceDb.length()}B")

        val localDb = copyToLocal(sourceDb)
        // READWRITE on our private copy: read-only open cannot replay the -wal sidecar.
        val db = SQLiteDatabase.openDatabase(localDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        db.use { database ->
            val cursor = database.rawQuery(
                """SELECT _id, start_timestamp, end_timestamp, duration, trip, electricity
                   FROM EnergyConsumption
                   WHERE is_deleted = 0 AND start_timestamp > ?
                   ORDER BY start_timestamp""",
                arrayOf(sinceTimestampSec.toString())
            )
            val results = mutableListOf<BydTripRecord>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    results.add(BydTripRecord(
                        id = c.getLong(0),
                        startTimestamp = c.getLong(1),
                        endTimestamp = c.getLong(2),
                        duration = c.getLong(3),
                        tripKm = c.getDouble(4),
                        electricityKwh = c.getDouble(5)
                    ))
                }
            }
            val zeroKm = results.count { it.tripKm <= 0.0 }
            Log.i(TAG, "readTripsSince: ${results.size} rows (${zeroKm} zero-km / idle drain)")
            results
        }
    }

    suspend fun readTrips(): List<BydTripRecord> = withContext(Dispatchers.IO) {
        val energyDir = energyDataDir()
        if (!energyDir.exists()) {
            Log.w(TAG, "readTrips: directory ${energyDir.path} missing — throwing")
            throw EnergyDataException("Директория energydata не найдена: ${energyDir.path}")
        }

        val sourceDb = findDbViaListFiles(energyDir)
            ?: findDbViaKnownNames(energyDir)
            ?: run {
                Log.w(TAG, "readTrips: no .db files in ${energyDir.path} — throwing")
                throw EnergyDataException("Не найдены файлы БД в ${energyDir.path}")
            }
        Log.i(TAG, "readTrips: source=${sourceDb.name} size=${sourceDb.length()}B")

        val localDb = copyToLocal(sourceDb)
        val results = readTripsFromDb(localDb)
        val zeroKm = results.count { it.tripKm <= 0.0 }
        Log.i(TAG, "readTrips: ${results.size} rows (${zeroKm} zero-km / idle drain)")
        results
    }

    /** Hook for tests to swap the dir. Default points at the real ENERGY_DIR_PATH. */
    internal open fun energyDataDir(): File = File(ENERGY_DIR_PATH)

    /**
     * Phase 1.5 mode switch: does the BYD energydata directory hold a readable trips DB?
     * Reuses existing find helpers — no new file probing logic.
     */
    fun isAvailable(): Boolean {
        val dir = energyDataDir()
        if (!dir.exists() || !dir.isDirectory) return false
        val db = findDbViaListFiles(dir) ?: findDbViaKnownNames(dir) ?: return false
        return db.canRead() && db.length() >= 16
    }

    /**
     * Fingerprint (lastModified, length) of the energydata DB currently on disk, or null
     * when no DB is present. Consumed by the dead-leftover detector (issue #63).
     *
     * The SQLite WAL sidecar is folded in (summed) so a live DB whose checkpoint is
     * deferred — main file frozen, all writes landing in -wal — still reads as changing.
     * An absent -wal contributes zeros, keeping the plain-file fingerprint intact.
     */
    fun sourceFingerprint(): Pair<Long, Long>? {
        val dir = energyDataDir()
        if (!dir.exists() || !dir.isDirectory) return null
        val db = findDbViaListFiles(dir) ?: findDbViaKnownNames(dir) ?: return null
        val wal = File(dir, db.name + "-wal")
        return (db.lastModified() + wal.lastModified()) to (db.length() + wal.length())
    }

    private fun findDbViaListFiles(dir: File): File? {
        val allFiles = dir.listFiles() ?: return null
        return allFiles
            .filter { it.isFile && (it.name.endsWith(".db", true) || it.name.endsWith(".sqlite", true)) }
            .maxByOrNull { it.lastModified() }
    }

    private fun findDbViaKnownNames(dir: File): File? {
        return KNOWN_DB_NAMES
            .map { File(dir, it) }
            .firstOrNull { it.exists() && it.length() > 0 }
    }

    internal fun copyToLocal(source: File): File {
        val extDir = context.getExternalFilesDir(null)
            ?: throw EnergyDataException("ExternalFilesDir не доступен")
        val localFile = File(extDir, LOCAL_DB_NAME)
        copyFile(source, localFile)
        // WAL may hold committed trips the main file does not have yet (AC-04):
        // copy the sidecars (or drop stale local ones) so SQLite replays them.
        // A torn -wal copy is safe: replay stops at the first bad frame checksum.
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(source.parentFile, source.name + suffix)
            val localSidecar = File(extDir, LOCAL_DB_NAME + suffix)
            if (sidecar.exists()) copyFile(sidecar, localSidecar) else localSidecar.delete()
        }
        return localFile
    }

    private fun copyFile(from: File, to: File) {
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { output -> input.copyTo(output) }
        }
    }

    private fun readTripsFromDb(dbFile: File): List<BydTripRecord> {
        // READWRITE on our private copy: read-only open cannot replay the -wal sidecar.
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
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
            results
        }
    }
}

/** Thrown when BYD energy data is unavailable or unreadable. */
class EnergyDataException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

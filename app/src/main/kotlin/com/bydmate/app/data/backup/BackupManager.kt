package com.bydmate.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.bydmate.app.data.local.database.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manifest stored inside every backup zip.
 * Contains enough metadata to guard against restoring a newer-schema backup.
 */
data class BackupManifest(
    val appVersionCode: Int,
    val dbSchemaVersion: Int,
    val createdAt: Long,
)

/** Parsed + size-validated contents of a backup zip. */
internal class BackupEntries(
    val dbBytes: ByteArray,
    val prefsJson: String,
    val manifestJson: String,
)

/**
 * Handles full-app config backup (export to zip) and restore (import from zip).
 *
 * The zip contains three entries:
 *   - bydmate.db     — Room database file (WAL folded in before copy)
 *   - prefs.json     — whitelisted SharedPreferences with explicit type tags
 *   - manifest.json  — version/schema metadata
 *
 * Inject via Hilt; pure serialization helpers live in the companion object so
 * unit tests can call them without Android.
 */
class BackupManager(
    private val context: Context,
    private val appDatabase: AppDatabase,
    private val prefsFileNames: List<String>,
) {

    companion object {

        private const val TAG = "BackupManager"

        private const val ENTRY_DB = "bydmate.db"
        private const val ENTRY_PREFS = "prefs.json"
        private const val ENTRY_MANIFEST = "manifest.json"

        // JSON type-tag constants
        private const val T_STRING = "String"
        private const val T_INT = "Int"
        private const val T_LONG = "Long"
        private const val T_FLOAT = "Float"
        private const val T_BOOLEAN = "Boolean"
        private const val T_STRING_SET = "StringSet"

        /**
         * Serialize a map of {fileName -> {key -> value}} into typed JSON.
         * Each value is wrapped as {"t": "<Type>", "v": <value>}.
         * Float values are stored as Double (Android org.json has no put(String, Float) overload).
         * Null values are skipped.
         */
        fun serializePrefs(data: Map<String, Map<String, Any?>>): String {
            val root = JSONObject()
            for ((fileName, entries) in data) {
                val fileObj = JSONObject()
                for ((key, rawValue) in entries) {
                    if (rawValue == null) continue
                    val entry = JSONObject()
                    when (rawValue) {
                        is String -> {
                            entry.put("t", T_STRING)
                            entry.put("v", rawValue)
                        }
                        is Int -> {
                            entry.put("t", T_INT)
                            entry.put("v", rawValue)
                        }
                        is Long -> {
                            entry.put("t", T_LONG)
                            entry.put("v", rawValue)
                        }
                        is Float -> {
                            // Cast to Double: Android org.json has no put(String, Float)
                            entry.put("t", T_FLOAT)
                            entry.put("v", rawValue.toDouble())
                        }
                        is Boolean -> {
                            entry.put("t", T_BOOLEAN)
                            entry.put("v", rawValue)
                        }
                        is Set<*> -> {
                            entry.put("t", T_STRING_SET)
                            val arr = JSONArray()
                            for (item in rawValue) {
                                arr.put(item?.toString() ?: "")
                            }
                            entry.put("v", arr)
                        }
                        else -> continue // unknown type, skip
                    }
                    fileObj.put(key, entry)
                }
                root.put(fileName, fileObj)
            }
            return root.toString()
        }

        /**
         * Deserialize typed JSON back to {fileName -> {key -> value}}.
         * Reconstructs exact Kotlin types: Int, Long, Float, Boolean, String, Set<String>.
         */
        fun deserializePrefs(json: String): Map<String, Map<String, Any?>> {
            val result = mutableMapOf<String, Map<String, Any?>>()
            val root = JSONObject(json)
            for (fileName in root.keys()) {
                val fileObj = root.getJSONObject(fileName)
                val entries = mutableMapOf<String, Any?>()
                for (key in fileObj.keys()) {
                    val entry = fileObj.getJSONObject(key)
                    val type = entry.getString("t")
                    val value: Any? = when (type) {
                        T_STRING -> entry.getString("v")
                        T_INT -> entry.getInt("v")
                        T_LONG -> entry.getLong("v")
                        T_FLOAT -> entry.getDouble("v").toFloat()
                        T_BOOLEAN -> entry.getBoolean("v")
                        T_STRING_SET -> {
                            val arr = entry.getJSONArray("v")
                            val set = mutableSetOf<String>()
                            for (i in 0 until arr.length()) {
                                set.add(arr.getString(i))
                            }
                            set as Set<String>
                        }
                        else -> null
                    }
                    if (value != null) entries[key] = value
                }
                result[fileName] = entries
            }
            return result
        }

        /**
         * Returns true if the backup can be safely restored onto the running schema version.
         * A backup made by a newer app (higher schema) is rejected because Room migrations
         * run forward only and cannot downgrade.
         */
        fun isRestorable(backupSchemaVersion: Int, currentSchemaVersion: Int): Boolean =
            backupSchemaVersion <= currentSchemaVersion

        // Restore ZIP hard limits (AC-13): a crafted "backup" must not OOM/ANR the app.
        // A heavy multi-year Room DB is tens of MB; these leave large headroom.
        internal const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
        internal const val MAX_TOTAL_BYTES = 768L * 1024 * 1024
        internal const val MAX_ENTRIES = 16

        /**
         * Reads and validates the three expected zip entries with hard size limits.
         * Duplicate expected entries, oversized data or a missing entry fail fast,
         * BEFORE any destructive restore step.
         */
        internal fun readBackupEntries(
            stream: InputStream,
            maxEntryBytes: Long = MAX_ENTRY_BYTES,
            maxTotalBytes: Long = MAX_TOTAL_BYTES,
        ): BackupEntries {
            var dbBytes: ByteArray? = null
            var prefsJson: String? = null
            var manifestJson: String? = null
            var total = 0L
            var entryCount = 0
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entryCount > MAX_ENTRIES) {
                        throw IllegalStateException("Файл бэкапа повреждён: слишком много записей в архиве")
                    }
                    val known = entry.name == ENTRY_DB || entry.name == ENTRY_PREFS || entry.name == ENTRY_MANIFEST
                    if (known) {
                        val alreadySeen = when (entry.name) {
                            ENTRY_DB -> dbBytes != null
                            ENTRY_PREFS -> prefsJson != null
                            else -> manifestJson != null
                        }
                        if (alreadySeen) {
                            throw IllegalStateException("Файл бэкапа повреждён: дублирующаяся запись ${entry.name}")
                        }
                        val bytes = readEntryBounded(zip, maxEntryBytes)
                        total += bytes.size
                        if (total > maxTotalBytes) {
                            throw IllegalStateException("Файл бэкапа слишком большой")
                        }
                        when (entry.name) {
                            ENTRY_DB -> dbBytes = bytes
                            ENTRY_PREFS -> prefsJson = bytes.toString(Charsets.UTF_8)
                            ENTRY_MANIFEST -> manifestJson = bytes.toString(Charsets.UTF_8)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return BackupEntries(
                dbBytes = dbBytes ?: throw incompleteBackup(),
                prefsJson = prefsJson ?: throw incompleteBackup(),
                manifestJson = manifestJson ?: throw incompleteBackup(),
            )
        }

        private fun incompleteBackup() = IllegalStateException(
            "Файл бэкапа повреждён или неполный. Ожидались записи: $ENTRY_DB, $ENTRY_PREFS, $ENTRY_MANIFEST"
        )

        /** Read the current zip entry, failing fast once [limit] bytes are exceeded. */
        private fun readEntryBounded(zip: ZipInputStream, limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = zip.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) {
                    throw IllegalStateException("Файл бэкапа повреждён: запись превышает допустимый размер")
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    /**
     * Export the full app state to a zip file in Downloads.
     * Returns the created File.
     *
     * Steps:
     *   1. Fold WAL and verify the checkpoint completed; abort export otherwise.
     *   2. Read the DB bytes under the write lock.
     *   3. Collect whitelisted SharedPreferences.
     *   4. Build manifest JSON.
     *   5. Write zip to Downloads.
     */
    fun export(): File {
        // 1. Fold WAL so the DB file is self-contained, and PROVE it happened (AC-02).
        //    PRAGMA wal_checkpoint(TRUNCATE) returns one row (busy, log, checkpointed);
        //    busy=1 means a concurrent reader/writer blocked the checkpoint and the WAL
        //    still holds frames absent from the main file — exporting would snapshot
        //    stale data while telling the user "success".
        // 2. Read the DB bytes under the write lock: beginTransaction() blocks writers,
        //    so nothing lands in the WAL between the checkpoint and the file read. A
        //    writer may still slip in between step 1 and the lock — detected via the
        //    WAL file size (TRUNCATE leaves it at 0 bytes) and retried.
        val dbFile = context.getDatabasePath("bydmate.db")
        val walFile = File(dbFile.parentFile, "bydmate.db-wal")
        var dbBytes: ByteArray? = null
        val supportDb = appDatabase.openHelper.writableDatabase
        for (attempt in 1..3) {
            if (!checkpointTruncate()) continue
            supportDb.beginTransaction()
            try {
                if (walFile.length() == 0L) {
                    dbBytes = dbFile.readBytes()
                    break
                }
            } finally {
                supportDb.endTransaction()
            }
        }
        val dbSnapshot = dbBytes ?: throw IllegalStateException(
            "База данных занята, экспорт прерван. Повторите попытку позже."
        )

        // 3. Collect SharedPreferences (only files with at least one entry)
        val prefsData = mutableMapOf<String, Map<String, Any?>>()
        for (name in prefsFileNames) {
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isNotEmpty()) {
                prefsData[name] = all
            }
        }
        val prefsJson = serializePrefs(prefsData)

        // 4. Build manifest
        val versionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) { 0 }
        val manifest = BackupManifest(
            appVersionCode = versionCode,
            dbSchemaVersion = AppDatabase.SCHEMA_VERSION,
            createdAt = System.currentTimeMillis(),
        )
        val manifestJson = JSONObject().apply {
            put("appVersionCode", manifest.appVersionCode)
            put("dbSchemaVersion", manifest.dbSchemaVersion)
            put("createdAt", manifest.createdAt)
        }.toString()

        // 5. Write zip to Downloads
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val zipFile = File(downloadsDir, "bydmate_backup_$timestamp.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_DB))
            zip.write(dbSnapshot)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_PREFS))
            zip.write(prefsJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        return zipFile
    }

    /** Runs PRAGMA wal_checkpoint(TRUNCATE); true only when fully checkpointed (busy=0). */
    private fun checkpointTruncate(): Boolean =
        try {
            appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                c.moveToFirst() && c.getInt(0) == 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "wal_checkpoint failed: ${e.message}")
            false
        }

    // -------------------------------------------------------------------------
    // Restore
    // -------------------------------------------------------------------------

    /**
     * Restore the full app state from a previously exported backup zip.
     *
     * Order is deliberate: everything is validated BEFORE the destructive replace
     * so a corrupt or incompatible zip never touches the live database.
     *
     * After this function returns the caller MUST restart the process so that
     * Room opens the replaced DB file fresh.
     */
    fun restore(uri: Uri) {
        // 1-2. Read + validate the zip entries under hard size limits (AC-13).
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Не удалось открыть файл бэкапа")
        val entries = inputStream.use { readBackupEntries(it) }

        // ---------------------------------------------------------------------
        // PRE-VALIDATION — everything that can fail MUST be checked here, before
        // a single destructive operation runs. A backup with a valid manifest but
        // a corrupt DB or malformed prefs.json must be rejected while the live DB
        // is still intact, never half-applied.
        // ---------------------------------------------------------------------

        // 2a. Manifest schema compatibility
        val manifestObj = JSONObject(entries.manifestJson)
        val backupSchema = manifestObj.getInt("dbSchemaVersion")
        if (!isRestorable(backupSchema, AppDatabase.SCHEMA_VERSION)) {
            throw IllegalStateException(
                "Бэкап создан более новой версией приложения " +
                "(схема $backupSchema, текущая ${AppDatabase.SCHEMA_VERSION}). " +
                "Обновите приложение перед восстановлением."
            )
        }

        // 2b. Deserialize prefs now — malformed JSON throws here, before any destructive step.
        val prefsMap = deserializePrefs(entries.prefsJson)

        // 2c. Write the DB bytes to a temp file and verify it is a real, intact SQLite
        //     database. openDatabase rejects a non-SQLite file; quick_check catches
        //     structural corruption. Bad file -> throw, temp deleted, live DB untouched.
        val targetDbFile = context.getDatabasePath("bydmate.db")
        val dbDir = targetDbFile.parentFile
        dbDir?.mkdirs()
        val tmpDbFile = File(dbDir, "bydmate.db.restore.tmp")
        tmpDbFile.delete()
        tmpDbFile.writeBytes(entries.dbBytes)
        validateSqliteFile(tmpDbFile)

        // ---------------------------------------------------------------------
        // DESTRUCTIVE PART — only reached once the backup is fully validated.
        // ---------------------------------------------------------------------

        // 3. Close the database so we can safely replace the file.
        appDatabase.close()

        // 4. Swap the validated temp file in via an atomic rename. The live bydmate.db
        //    is never absent: it is replaced atomically. If rename fails the original is
        //    still in place and NOTHING has been mutated yet, so we abort (throw) rather
        //    than risk destroying it with a non-atomic delete+copy. This also closes the
        //    race where the foreground TrackingService could re-open Room mid-restore and
        //    find a missing file.
        //
        //    A successful rename is the POINT OF NO RETURN: from here the app state is
        //    already changed, so no later step may throw past the caller's restart. File
        //    deletes below do not throw, and the prefs loop is best-effort (see below).
        if (!tmpDbFile.renameTo(targetDbFile)) {
            tmpDbFile.delete()
            throw IllegalStateException("Не удалось заменить файл базы данных при восстановлении")
        }
        // Drop stale WAL/SHM left from the old DB AFTER the swap. The restored file is
        // self-contained (WAL was folded in at export time).
        File(dbDir, "bydmate.db-wal").delete()
        File(dbDir, "bydmate.db-shm").delete()

        // 5. Restore SharedPreferences.
        // FULL REPLACE: clear EVERY whitelisted file first, even files absent from the
        // backup (they were empty at export time). Iterating only over prefsMap would let
        // stale prefs on the current device survive a "full replace".
        for (fileName in prefsFileNames) {
            val editor = context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit()
            editor.clear()
            val entries = prefsMap[fileName]
            if (entries != null) {
                for ((key, value) in entries) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>)
                        }
                    }
                }
            }
            if (!editor.commit()) {
                // Past the point of no return: the DB is already swapped. A failed prefs
                // commit (rare, disk-level error) must NOT throw here — that would skip the
                // caller's restart and freeze the app half-restored. Log and continue so
                // the remaining files still apply and the process still restarts.
                Log.w(TAG, "Failed to commit prefs file during restore: $fileName")
            }
        }
        // Caller is responsible for restarting the process after this returns.
    }

    /**
     * Verify [file] is a readable, structurally intact SQLite database.
     * Throws IllegalStateException (and deletes the temp file) if it is not a SQLite file
     * or fails quick_check. Opened read-only so a backup from an older (but compatible)
     * schema is not migrated here.
     */
    private fun validateSqliteFile(file: File) {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            file.delete()
            throw IllegalStateException("Файл базы данных в бэкапе повреждён или не является базой SQLite", e)
        }
        val ok = try {
            db.rawQuery("PRAGMA quick_check", null).use { c ->
                c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
            }
        } catch (e: SQLiteException) {
            false
        } finally {
            db.close()
        }
        if (!ok) {
            file.delete()
            throw IllegalStateException("Файл базы данных в бэкапе не прошёл проверку целостности")
        }
    }
}

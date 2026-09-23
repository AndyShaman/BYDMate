package com.bydmate.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import com.bydmate.app.camera.BlindSpotPreferences
import com.bydmate.app.cluster.ClusterFrameUi7
import com.bydmate.app.cluster.ClusterJournal
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.autoservice.AdbRestorePreferencesImpl
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.hud.HudController
import com.bydmate.app.service.A11yRecoveryGate
import com.bydmate.app.split.SplitPreferencesImpl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    /** The database came as `bydmate.part.db`: an archive without some of the parts. */
    val partial: Boolean = false,
)

/**
 * Handles full-app config backup (export to zip) and restore (import from zip).
 *
 * The zip contains three entries:
 *   - bydmate.db     — Room database file (WAL folded in before copy); `bydmate.part.db` when the
 *                      archive lacks some [BackupPart]s, so an older app refuses to swap it in whole
 *   - prefs.json     — whitelisted SharedPreferences with explicit type tags (`{}` without Settings)
 *   - manifest.json  — version/schema metadata and the `parts` it carries (absent = all of them)
 *
 * Inject via Hilt; pure serialization helpers live in the companion object so
 * unit tests can call them without Android.
 */
class BackupManager(
    private val context: Context,
    private val appDatabase: AppDatabase,
    private val prefsFileNames: List<String>,
    /** Per-file keys that stay on this device: never exported, never overwritten by a restore. */
    private val excludedPrefsKeys: Map<String, Set<String>> = emptyMap(),
) {

    companion object {

        private const val TAG = "BackupManager"

        /**
         * Files that joined the whitelist after archives without them were already in the wild.
         * Restore leaves such a file alone when the archive lacks it; a new file goes here.
         */
        val PREFS_FILES_ADDED_LATER = listOf(
            // Without the ADB-restore toggle the helper daemon never comes back after the first
            // reboot on firmwares that close port 5555, and every daemon-based feature dies.
            AdbRestorePreferencesImpl.PREFS_NAME,
            BlindSpotPreferences.PREFS_NAME,
            SplitPreferencesImpl.PREFS_NAME,
            HudController.PREFS_NAME,
        )

        /** SharedPreferences files carried by a backup. */
        val PREFS_FILES = listOf(
            "bydmate_locale",
            "bydmate_widget",
            ClusterProjectionManager.PREFS_NAME,
            "automation",
            "update_prefs",
            "bydmate_range_prefs",
            // Durable user voice/agent settings (AC-03). Deliberately NOT included:
            // energydata_sync / energydata_liveness / seat_channel / window_channel —
            // per-device learned state that must not migrate to another car.
            "voice",
        ) + PREFS_FILES_ADDED_LATER

        /**
         * Runtime markers of the session that wrote them and car-specific probe results.
         * An entry ending in '*' excludes every key with that prefix.
         */
        val EXCLUDED_PREFS_KEYS: Map<String, Set<String>> = mapOf(
            HudController.PREFS_NAME to setOf(HudController.KEY_SUPPORTED),
            ClusterProjectionManager.PREFS_NAME to setOf(
                ClusterProjectionManager.KEY_LAST_VD_ID,
                ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID,
                ClusterProjectionManager.KEY_COMPOSITOR_POWERED,
                ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING,
                A11yRecoveryGate.KEY_LAST_ATTEMPT_ELAPSED_MS,
                A11yRecoveryGate.KEY_FAIL_STREAK,
                ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING,
                ClusterProjectionManager.KEY_DIRECT_FORCED,
                ClusterFrameUi7.KEY_SAVED_CENTER,
                ClusterFrameUi7.KEY_SAVED_LEFT,
                ClusterFrameUi7.KEY_SAVED_MENU,
                ClusterJournal.KEY_JOURNAL,
                // SplitFreeformVerdict: latched per boot count of this head unit.
                "split_ff_*",
                ClusterProjectionManager.KEY_DENSITY_UNSAFE_PREFIX + "*",
            ),
            // Cooldown of the last adb_wifi_enabled write: a record from the source device would
            // suppress the first write on a fresh install on the same Wi-Fi.
            AdbRestorePreferencesImpl.PREFS_NAME to setOf(
                AdbRestorePreferencesImpl.KEY_LAST_WRITE_NETWORK,
                AdbRestorePreferencesImpl.KEY_LAST_WRITE_AT,
            ),
            // service_start session of this head unit's boot (#177): another car's boot id and
            // heartbeat would decide whether the first start here fires.
            AutomationEngine.PREFS_NAME to setOf(
                AutomationEngine.KEY_SERVICE_START_BOOT_ID,
                AutomationEngine.KEY_SERVICE_START_LAST_SEEN_ELAPSED,
                AutomationEngine.KEY_SERVICE_START_LAST_SEEN_UPTIME,
            ),
        )

        internal fun isExcluded(patterns: Set<String>, key: String): Boolean =
            patterns.any { if (it.endsWith('*')) key.startsWith(it.dropLast(1)) else key == it }

        private const val ENTRY_DB = "bydmate.db"
        private const val ENTRY_PART_DB = "bydmate.part.db"
        private const val MANIFEST_PARTS = "parts"
        private const val ENTRY_PREFS = "prefs.json"
        private const val ENTRY_MANIFEST = "manifest.json"

        private const val BACKUP_FILE_PREFIX = "bydmate_backup_"
        private const val BACKUP_FILE_SUFFIX = ".zip"

        /** Export zips in [dir] (name mask used by export()), newest first. */
        fun listBackupFiles(dir: File): List<File> =
            (dir.listFiles() ?: emptyArray())
                .filter {
                    it.isFile && it.name.startsWith(BACKUP_FILE_PREFIX) && it.name.endsWith(BACKUP_FILE_SUFFIX)
                }
                .sortedByDescending { it.lastModified() }

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
         * Duplicate expected entries (also both database names at once), oversized data or a
         * missing entry fail fast, BEFORE any destructive restore step.
         *
         * [dbEntryNames] = the database entry names accepted; `setOf("bydmate.db")` is what v3.17.5
         * and older read, so they reject a partial archive instead of swapping it in whole.
         */
        internal fun readBackupEntries(
            stream: InputStream,
            maxEntryBytes: Long = MAX_ENTRY_BYTES,
            maxTotalBytes: Long = MAX_TOTAL_BYTES,
            dbEntryNames: Set<String> = setOf(ENTRY_DB, ENTRY_PART_DB),
        ): BackupEntries {
            // Entry bytes by slot: ENTRY_DB for either database name, the entry name otherwise.
            val found = mutableMapOf<String, ByteArray>()
            var dbEntryName: String? = null
            var total = 0L
            ZipInputStream(stream).use { zip ->
                generateSequence { zip.nextEntry }.forEachIndexed { index, entry ->
                    check(index < MAX_ENTRIES) { "Файл бэкапа повреждён: слишком много записей в архиве" }
                    val slot = when (entry.name) {
                        in dbEntryNames -> ENTRY_DB
                        ENTRY_PREFS, ENTRY_MANIFEST -> entry.name
                        else -> null
                    }
                    if (slot != null) {
                        check(slot !in found) { "Файл бэкапа повреждён: дублирующаяся запись ${entry.name}" }
                        val bytes = readEntryBounded(zip, maxEntryBytes)
                        total += bytes.size
                        check(total <= maxTotalBytes) { "Файл бэкапа слишком большой" }
                        found[slot] = bytes
                        if (slot == ENTRY_DB) dbEntryName = entry.name
                    }
                    zip.closeEntry()
                }
            }
            fun take(slot: String) = found[slot] ?: throw incompleteBackup()
            return BackupEntries(
                dbBytes = take(ENTRY_DB),
                prefsJson = take(ENTRY_PREFS).toString(Charsets.UTF_8),
                manifestJson = take(ENTRY_MANIFEST).toString(Charsets.UTF_8),
                partial = dbEntryName == ENTRY_PART_DB,
            )
        }

        /** Parts listed in a manifest; a manifest without `parts` comes from a full archive. */
        internal fun manifestParts(manifest: JSONObject): Set<BackupPart> {
            val array = manifest.optJSONArray(MANIFEST_PARTS) ?: return BackupPart.ALL
            return (0 until array.length()).mapNotNull { BackupPart.fromId(array.optString(it)) }.toSet()
        }

        /** Largest manifest.json [archiveParts] reads; a real one is under 200 bytes. */
        private const val MAX_MANIFEST_BYTES = 64L * 1024

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

        private fun putTyped(editor: SharedPreferences.Editor, key: String, value: Any?) {
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

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    /**
     * Export [parts] of the app state to a zip file in Downloads.
     * Returns the created File.
     *
     * Steps:
     *   1. Fold WAL and verify the checkpoint completed; abort export otherwise.
     *   2. Read the DB bytes under the write lock.
     *   3. Drop runtime state and the parts not asked for from a temp copy.
     *   4. Collect whitelisted SharedPreferences (Settings only).
     *   5. Build manifest JSON.
     *   6. Write zip to Downloads.
     *
     * Serialized: a manual export and the auto backup worker may run at once, and two
     * checkpoint/read passes over the same WAL would fight each other.
     */
    fun export(parts: Set<BackupPart> = BackupPart.ALL): File = synchronized(exportLock) { exportUnlocked(parts) }

    private val exportLock = Any()

    /**
     * Two exports within one second (manual + auto run) must not overwrite each other: adds
     * _2, _3… when the name is taken, also by the auto runner's renamed copy of it.
     */
    private fun freeBackupFile(dir: File, timestamp: String): File = generateSequence(1) { it + 1 }
        .map { n -> timestamp + (if (n == 1) "" else "_$n") + BACKUP_FILE_SUFFIX }
        .first { name ->
            !File(dir, BACKUP_FILE_PREFIX + name).exists() && !File(dir, AutoBackupRunner.AUTO_PREFIX + name).exists()
        }
        .let { File(dir, BACKUP_FILE_PREFIX + it) }

    private fun exportUnlocked(parts: Set<BackupPart>): File {
        // 1-2. Self-contained snapshot of the live database.
        // 3. Runtime state never travels, so even a full archive goes through the trimmed copy.
        val dbSnapshot = BackupDatabaseFiles.trimmedCopy(snapshotLiveDb(), parts, context.cacheDir)

        // 4. Collect SharedPreferences. Every whitelisted file is written, even an empty one:
        //    restore reads "file absent" as "made before this file joined the whitelist".
        val prefsJson = if (BackupPart.SETTINGS in parts) {
            val prefsData = mutableMapOf<String, Map<String, Any?>>()
            for (name in prefsFileNames) {
                val excluded = excludedPrefsKeys[name].orEmpty()
                prefsData[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    .filterKeys { !isExcluded(excluded, it) }
            }
            serializePrefs(prefsData)
        } else {
            "{}"
        }

        // 5. Build manifest
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
            put(MANIFEST_PARTS, JSONArray(BackupPart.entries.filter { it in parts }.map { it.id }))
        }.toString()

        // 6. Write zip to Downloads
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val zipFile = freeBackupFile(downloadsDir, timestamp)

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry(if (parts == BackupPart.ALL) ENTRY_DB else ENTRY_PART_DB))
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

    /**
     * Bytes of the live database with the WAL folded in. Caller holds [exportLock].
     *
     * 1. Fold WAL so the DB file is self-contained, and PROVE it happened (AC-02).
     *    PRAGMA wal_checkpoint(TRUNCATE) returns one row (busy, log, checkpointed);
     *    busy=1 means a concurrent reader/writer blocked the checkpoint and the WAL
     *    still holds frames absent from the main file — exporting would snapshot
     *    stale data while telling the user "success".
     * 2. Read the DB bytes under the write lock: beginTransaction() blocks writers,
     *    so nothing lands in the WAL between the checkpoint and the file read. A
     *    writer may still slip in between step 1 and the lock — detected via the
     *    WAL file size (TRUNCATE leaves it at 0 bytes) and retried.
     */
    private fun snapshotLiveDb(): ByteArray {
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
        return dbBytes ?: throw IllegalStateException(
            "База данных занята, экспорт прерван. Повторите попытку позже."
        )
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

    /** Export zips in the public Download folder, newest first. */
    fun listBackups(): List<File> =
        listBackupFiles(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))

    /** Parts [file] carries, read from its manifest alone; the restore dialog offers only these. */
    fun archiveParts(file: File): Set<BackupPart> {
        val manifestJson = try {
            ZipInputStream(file.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }
                    .take(MAX_ENTRIES)
                    .firstOrNull { it.name == ENTRY_MANIFEST }
                    ?.let { readEntryBounded(zip, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8) }
            }
        } catch (e: IOException) {
            throw IllegalStateException("Не удалось открыть файл бэкапа", e)
        } ?: throw incompleteBackup()
        return manifestParts(JSONObject(manifestJson))
    }

    /**
     * Restore [parts] of the app state from a previously exported backup zip.
     *
     * A full archive restored whole replaces the database file. Anything less is merged: the
     * chosen parts of the archive replace the same parts of a copy of the live database, and the
     * copy replaces the file. The other parts and this head unit's runtime state stay as they are.
     *
     * Order is deliberate: everything is validated BEFORE the destructive replace
     * so a corrupt or incompatible zip never touches the live database.
     *
     * After this function returns the caller MUST restart the process so that
     * Room opens the replaced DB file fresh.
     */
    fun restore(file: File, parts: Set<BackupPart> = BackupPart.ALL) {
        // Read straight from the public Download folder via the File API: on some firmwares
        // (Yuan Plus, DiLink 3.0) the ACTION_OPEN_DOCUMENT handler returns a URI without a
        // read grant, so the SAF path fails with a SecurityException (#223).
        val inputStream: InputStream = try {
            file.inputStream()
        } catch (e: IOException) {
            throw IllegalStateException("Не удалось открыть файл бэкапа", e)
        }
        // 1-2. Read + validate the zip entries under hard size limits (AC-13).
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
        check(isRestorable(backupSchema, AppDatabase.SCHEMA_VERSION)) { BackupDatabaseFiles.newerArchiveMessage(backupSchema) }

        // 2b. Parts: a trimmed database must never pass for a full one.
        val archiveParts = manifestParts(manifestObj)
        check(entries.partial != (archiveParts == BackupPart.ALL)) {
            "Файл бэкапа повреждён: состав архива не совпадает с его описанием"
        }
        val selected = parts intersect archiveParts
        check(selected.isNotEmpty()) { "В архиве нет выбранных частей" }

        // 2c. Deserialize prefs now — malformed JSON throws here, before any destructive step.
        val prefsMap = deserializePrefs(entries.prefsJson)

        // 2d. Write the DB bytes to a temp file and verify it is a real, intact SQLite
        //     database. openDatabase rejects a non-SQLite file; quick_check catches
        //     structural corruption. Bad file -> throw, temp deleted, live DB untouched.
        val targetDbFile = context.getDatabasePath("bydmate.db")
        val dbDir = targetDbFile.parentFile
        dbDir?.mkdirs()
        val tmpDbFile = File(dbDir, "bydmate.db.restore.tmp")
        BackupDatabaseFiles.deleteWithSideFiles(tmpDbFile)
        tmpDbFile.writeBytes(entries.dbBytes)
        BackupDatabaseFiles.validate(tmpDbFile)

        if (selected == BackupPart.ALL) {
            // Under the export lock: an auto backup running at this moment must not checkpoint or
            // read the database while it is closed and swapped.
            synchronized(exportLock) { swapIn(tmpDbFile, prefsMap) }
        } else {
            mergeAndSwap(tmpDbFile, selected, prefsMap.takeIf { BackupPart.SETTINGS in selected })
        }
        // Caller is responsible for restarting the process after this returns.
    }

    /**
     * Replaces [selected] parts of a copy of the live database with the same parts of [archiveFile],
     * then swaps the copy in. Room migrates an older archive first, so both sides share one schema.
     */
    private fun mergeAndSwap(archiveFile: File, selected: Set<BackupPart>, prefsMap: Map<String, Map<String, Any?>>?) {
        val mergedFile = File(archiveFile.parentFile, "bydmate.db.merge.tmp")
        try {
            BackupDatabaseFiles.migrate(context, archiveFile)
            synchronized(exportLock) {
                BackupDatabaseFiles.deleteWithSideFiles(mergedFile)
                mergedFile.writeBytes(snapshotLiveDb())
                BackupDatabaseFiles.merge(mergedFile, archiveFile, selected)
                swapIn(mergedFile, prefsMap)
            }
        } finally {
            BackupDatabaseFiles.deleteWithSideFiles(archiveFile)
            BackupDatabaseFiles.deleteWithSideFiles(mergedFile)
        }
    }

    /**
     * DESTRUCTIVE PART — only reached once the backup is fully validated. Caller holds [exportLock].
     * [prefsMap] null = SharedPreferences stay as they are.
     */
    private fun swapIn(dbFile: File, prefsMap: Map<String, Map<String, Any?>>?) {
        val targetDbFile = context.getDatabasePath("bydmate.db")
        val dbDir = targetDbFile.parentFile

        // Mark the next start as the first one after a restore BEFORE anything is replaced,
        // so a process death mid-restore still runs PostRestoreCheck (overlay, models).
        PostRestoreCheck.markPending(context)

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
        if (!dbFile.renameTo(targetDbFile)) {
            dbFile.delete()
            throw IllegalStateException("Не удалось заменить файл базы данных при восстановлении")
        }
        // Drop stale WAL/SHM left from the old DB AFTER the swap. The restored file is
        // self-contained (WAL was folded in at export time).
        File(dbDir, "bydmate.db-wal").delete()
        File(dbDir, "bydmate.db-shm").delete()

        // 5. Restore SharedPreferences.
        // FULL REPLACE of every whitelisted file, except the per-device keys in
        // excludedPrefsKeys, which keep this device's values. A file absent from the backup
        // is cleared too (older exports skipped empty files), unless it is in
        // PREFS_FILES_ADDED_LATER: then the backup predates that file joining the whitelist,
        // and clearing it would wipe a live setting (adb_restore).
        if (prefsMap == null) return
        for (fileName in prefsFileNames) {
            val entries = prefsMap[fileName] ?: if (fileName in PREFS_FILES_ADDED_LATER) continue else emptyMap()
            val excluded = excludedPrefsKeys[fileName].orEmpty()
            val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.keys.filterNot { isExcluded(excluded, it) }.forEach { editor.remove(it) }
            for ((key, value) in entries) {
                if (!isExcluded(excluded, key)) putTyped(editor, key, value)
            }
            if (!editor.commit()) {
                // Past the point of no return: the DB is already swapped. A failed prefs
                // commit (rare, disk-level error) must NOT throw here — that would skip the
                // caller's restart and freeze the app half-restored. Log and continue so
                // the remaining files still apply and the process still restarts.
                Log.w(TAG, "Failed to commit prefs file during restore: $fileName")
            }
        }
    }
}

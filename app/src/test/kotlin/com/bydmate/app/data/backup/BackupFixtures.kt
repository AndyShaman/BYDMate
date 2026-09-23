package com.bydmate.app.data.backup

import android.content.Context
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.bydmate.app.data.local.database.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Real Room databases and archives for the backup tests. */
internal object BackupFixtures {

    const val TOKEN = "tg-secret-7f3a9c1e5b"

    /** Fresh cursor per call: the snapshot closes it via .use on every retry attempt. */
    fun checkpointCursor(busy: Int = 0) =
        MatrixCursor(arrayOf("busy", "log", "checkpointed")).apply { addRow(arrayOf(busy, 0, 0)) }

    /** An empty database of the current schema, created by Room itself, at getDatabasePath([name]). */
    fun createCurrentDb(context: Context, name: String): File {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        db.openHelper.writableDatabase
        db.close()
        return context.getDatabasePath(name)
    }

    fun <T> withDb(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    /**
     * One row in every table of the current schema; [tag] marks the values so a test can tell
     * which side a row came from. Trip and charge ids are [id].
     */
    fun seedCurrent(db: SQLiteDatabase, tag: String, id: Long, token: String = TOKEN) {
        db.execSQL("INSERT INTO trips (id, start_ts, source) VALUES ($id, 1000, '$tag')")
        db.execSQL("INSERT INTO trip_points (trip_id, timestamp, lat, lon) VALUES ($id, 1001, 53.9, 27.5)")
        db.execSQL("INSERT INTO trip_tombstones (byd_id) VALUES ($id)")
        db.execSQL(
            "INSERT INTO charges (id, start_ts, status, merged_count, cost_manual, detection_source) " +
                "VALUES ($id, 2000, 'COMPLETED', 0, 0, '$tag')"
        )
        db.execSQL("INSERT INTO charge_points (charge_id, timestamp) VALUES ($id, 2001)")
        db.execSQL("INSERT INTO battery_snapshots (timestamp, soc_start, soc_end, kwh_charged) VALUES (3000, 20, 80, 40.0)")
        db.execSQL("INSERT INTO idle_drains (start_ts) VALUES (4000)")
        db.execSQL(
            "INSERT INTO tariff_periods (start_ts, home_rate, dc_rate, ac_loss_pct, dc_loss_pct, trip_rule) " +
                "VALUES (0, 0.2, 0.7, 10, 5, '$tag')"
        )
        db.execSQL(
            "INSERT INTO automation_rules (name, enabled, trigger_logic, triggers, actions, cooldown_seconds, " +
                "require_park, confirm_before_execute, trigger_count, created_at) VALUES ('$tag', 1, 'AND', '[]', '[]', 0, 0, 0, 0, 1)"
        )
        db.execSQL("INSERT INTO places (name, lat, lon, radius_m, created_at) VALUES ('$tag', 53.9, 27.5, 100, 1)")
        db.execSQL(
            "INSERT INTO automation_log (rule_id, rule_name, triggered_at, triggers_snapshot, actions_result, success) " +
                "VALUES (1, '$tag', 1, '', '', 1)"
        )
        db.execSQL("INSERT INTO vehicle_write_log (ts, actionName, dev, fid, requested, status) VALUES (1, '$tag', 1, 1, 1, 0)")
        db.execSQL("INSERT INTO odometer_samples (mileage_km, timestamp) VALUES (100.0, 1)")
        db.execSQL("INSERT INTO last_state (id, ts, energydata_available, open_trip_id) VALUES (1, 5, 1, $id)")
        setSetting(db, "currency", tag)
        setSetting(db, "trip1_reset_ts", tag)
        setSetting(db, "tg_backup_token", token)
        setSetting(db, "last_known_soc", tag)
    }

    fun setSetting(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    fun setting(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { it.moveToFirst(); it.getInt(0) }

    fun text(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    /** Archive zip in the export format, in a fresh temp file; [parts] null = an old manifest without `parts`. */
    fun archive(
        dbBytes: ByteArray,
        parts: Set<BackupPart>?,
        schema: Int = AppDatabase.SCHEMA_VERSION,
        prefs: Map<String, Map<String, Any?>> = emptyMap(),
        dbEntry: String = if (parts == null || parts == BackupPart.ALL) "bydmate.db" else "bydmate.part.db",
    ): File {
        val manifest = JSONObject().apply {
            put("appVersionCode", 1)
            put("dbSchemaVersion", schema)
            put("createdAt", 0L)
            if (parts != null) put("parts", JSONArray(parts.map { it.id }))
        }.toString()
        val zip = File.createTempFile("bydmate_backup_", ".zip").apply { deleteOnExit() }
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry(dbEntry)); z.write(dbBytes); z.closeEntry()
            z.putNextEntry(ZipEntry("prefs.json")); z.write(BackupManager.serializePrefs(prefs).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
        }
        return zip
    }

    fun entryBytes(zip: File, name: String): ByteArray? =
        ZipFile(zip).use { z -> z.getEntry(name)?.let { z.getInputStream(it).readBytes() } }

    /** The database entry of [zip] written to a file under [dir] for SQL checks. */
    fun openEntryDb(zip: File, name: String, dir: File): File =
        File(dir, "entry-${System.nanoTime()}.db").apply { writeBytes(entryBytes(zip, name)!!) }
}

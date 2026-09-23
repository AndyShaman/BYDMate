package com.bydmate.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.di.AppModule
import java.io.File

/**
 * SQL on detached copies of the database (#238): trimming an export to its parts and merging the
 * chosen parts of an archive into a copy of the live database. Never touches the live file.
 */
internal object BackupDatabaseFiles {

    /**
     * [snapshot] without runtime state and without the parts outside [parts]. Deleted rows must not
     * survive in free pages of the file (a token in an archive without Keys): secure_delete zeroes
     * them and VACUUM rebuilds the file from live rows only.
     */
    fun trimmedCopy(snapshot: ByteArray, parts: Set<BackupPart>, tempDir: File): ByteArray {
        val tmp = File.createTempFile("backup_trim_", ".db", tempDir)
        try {
            tmp.writeBytes(snapshot)
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
                inTransaction(db) { dropRows(db, parts) }
                db.execSQL("VACUUM")
                check(quickCheck(db)) { "Снимок базы для экспорта не прошёл проверку целостности" }
            }
            return tmp.readBytes()
        } finally {
            deleteWithSideFiles(tmp)
        }
    }

    private fun dropRows(db: SQLiteDatabase, parts: Set<BackupPart>) {
        val dropped = BackupPart.entries.filter { it !in parts }
        (BackupParts.RUNTIME_TABLES + dropped.flatMap(BackupParts::tablesOf))
            .forEach { db.execSQL("DELETE FROM `$it`") }
        val runtimeKeys = BackupParts.RUNTIME_SETTINGS_KEYS.toTypedArray()
        db.delete("settings", "key IN ${List(runtimeKeys.size) { "?" }.joinToString(",", "(", ")")}", runtimeKeys)
        for (part in dropped) {
            val (where, args) = BackupParts.settingsKeyFilter(part)
            db.delete("settings", where, args)
        }
    }

    /**
     * Replaces [selected] parts of [target] with the same parts of [archive]; both share one schema.
     * The runtime tables and keys of [target] stay as they are.
     */
    fun merge(target: File, archive: File, selected: Set<BackupPart>) {
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ATTACH DATABASE ? AS archive", arrayOf(archive.path))
            inTransaction(db) {
                selected.forEach { replacePart(db, it) }
                // The open trip of the live state points into the history just replaced.
                if (BackupPart.TABLES in selected) db.execSQL("UPDATE last_state SET open_trip_id = NULL")
            }
            db.execSQL("DETACH DATABASE archive")
            check(quickCheck(db)) { "База после слияния с бэкапом не прошла проверку целостности" }
        }
    }

    /** Tables and settings keys of [part] in `main` become exactly those of `archive`. */
    private fun replacePart(db: SQLiteDatabase, part: BackupPart) {
        val tables = BackupParts.tablesOf(part)
        tables.asReversed().forEach { db.execSQL("DELETE FROM main.`$it`") }
        for (table in tables) {
            // Named columns: a migrated database appends added columns, a fresh one has them in
            // entity order, so the two may list the same columns in a different order.
            val columns = db.rawQuery("PRAGMA main.table_info(`$table`)", null).use { c ->
                val name = c.getColumnIndexOrThrow("name")
                buildList { while (c.moveToNext()) add("`${c.getString(name)}`") }
            }.joinToString(",")
            db.execSQL("INSERT INTO main.`$table` ($columns) SELECT $columns FROM archive.`$table`")
        }
        val (where, args) = BackupParts.settingsKeyFilter(part)
        db.execSQL("DELETE FROM main.settings WHERE $where", args)
        db.execSQL("INSERT INTO main.settings (key, value) SELECT key, value FROM archive.settings WHERE $where", args)
    }

    /**
     * Brings [archive] (a file in the app's database folder) of an older schema to the current one
     * through the app's own migrations; Room validates the result against the schema.
     */
    fun migrate(context: Context, archive: File) {
        val version = SQLiteDatabase.openDatabase(archive.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        check(version <= AppDatabase.SCHEMA_VERSION) { newerArchiveMessage(version) }
        // Room would create an empty schema over it and the merge would wipe the chosen parts.
        check(version >= 1) { "Файл базы данных в бэкапе не является базой BYDMate" }
        if (version == AppDatabase.SCHEMA_VERSION) return
        val archiveDb = AppModule.withMigrations(Room.databaseBuilder(context, AppDatabase::class.java, archive.name))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            archiveDb.openHelper.writableDatabase
        } finally {
            archiveDb.close()
        }
    }

    fun newerArchiveMessage(schema: Int) =
        "Бэкап создан более новой версией приложения (схема $schema, текущая ${AppDatabase.SCHEMA_VERSION}). " +
            "Обновите приложение перед восстановлением."

    /**
     * Verify [file] is a readable, structurally intact SQLite database.
     * Throws IllegalStateException (and deletes the temp file) if it is not a SQLite file
     * or fails quick_check. Opened read-only so a backup from an older (but compatible)
     * schema is not migrated here.
     */
    fun validate(file: File) {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            file.delete()
            throw IllegalStateException("Файл базы данных в бэкапе повреждён или не является базой SQLite", e)
        }
        val ok = try {
            quickCheck(db)
        } catch (_: SQLiteException) {
            false
        } finally {
            db.close()
        }
        if (!ok) {
            file.delete()
            error("Файл базы данных в бэкапе не прошёл проверку целостности")
        }
    }

    private fun quickCheck(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA quick_check", null).use { c ->
            c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
        }

    private inline fun inTransaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A temp database with the side files SQLite may have left next to it. */
    fun deleteWithSideFiles(file: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }
}

package com.bydmate.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** #223: restore reads the backup through the File API, no SAF URI involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackupManagerRestoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appDatabase = mockk<AppDatabase>(relaxed = true)

    private fun backupZip(): Pair<File, ByteArray> {
        val dbFile = tmp.newFile("src.db").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE t (x INTEGER)")
            db.execSQL("INSERT INTO t VALUES (42)")
        }
        val dbBytes = dbFile.readBytes()
        val prefsJson = BackupManager.serializePrefs(mapOf("automation" to mapOf("restored_key" to "yes")))
        val manifestJson = JSONObject().apply {
            put("appVersionCode", 1)
            put("dbSchemaVersion", AppDatabase.SCHEMA_VERSION)
            put("createdAt", 0L)
        }.toString()
        val zip = File(tmp.root, "bydmate_backup_20260101_000000.zip")
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("bydmate.db")); z.write(dbBytes); z.closeEntry()
            z.putNextEntry(ZipEntry("prefs.json")); z.write(prefsJson.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifestJson.toByteArray()); z.closeEntry()
        }
        return zip to dbBytes
    }

    @Test
    fun `restore from file swaps db and applies prefs`() {
        val prefs = context.getSharedPreferences("automation", Context.MODE_PRIVATE)
        prefs.edit().putString("stale_key", "old").commit()
        val (zip, dbBytes) = backupZip()

        BackupManager(context, appDatabase, listOf("automation")).restore(zip)

        assertArrayEquals(dbBytes, context.getDatabasePath("bydmate.db").readBytes())
        assertEquals("yes", prefs.getString("restored_key", null))
        assertTrue(!prefs.contains("stale_key"))
    }

    @Test
    fun `listBackupFiles keeps only export mask, newest first`() {
        val dir = tmp.newFolder("Download")
        val older = File(dir, "bydmate_backup_20260101_000000.zip").apply { writeText("a"); setLastModified(1_000_000L) }
        val newer = File(dir, "bydmate_backup_20260202_000000.zip").apply { writeText("b"); setLastModified(2_000_000L) }
        File(dir, "other.zip").writeText("c")
        File(dir, "bydmate_backup_20260303_000000.txt").writeText("d")
        File(dir, "bydmate_backup_dir.zip").mkdirs()

        assertEquals(listOf(newer, older), BackupManager.listBackupFiles(dir))
    }

    @Test
    fun `listBackupFiles on missing dir is empty`() {
        assertEquals(emptyList<File>(), BackupManager.listBackupFiles(File(tmp.root, "absent")))
    }
}

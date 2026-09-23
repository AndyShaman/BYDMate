package com.bydmate.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.camera.BlindSpotPreferences
import com.bydmate.app.data.autoservice.AdbRestorePreferencesImpl
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.util.AppStrings
import com.bydmate.app.hud.HudController
import com.bydmate.app.split.SplitPreferencesImpl
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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

    private fun backupZip(
        prefs: Map<String, Map<String, Any?>> = mapOf("automation" to mapOf("restored_key" to "yes")),
        name: String = "src",
        dbBytes: ByteArray = currentDbBytes(name),
    ): Pair<File, ByteArray> {
        val prefsJson = BackupManager.serializePrefs(prefs)
        val manifestJson = JSONObject().apply {
            put("appVersionCode", 1)
            put("dbSchemaVersion", AppDatabase.SCHEMA_VERSION)
            put("createdAt", 0L)
        }.toString()
        val zip = File(tmp.root, "bydmate_backup_$name.zip")
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("bydmate.db")); z.write(dbBytes); z.closeEntry()
            z.putNextEntry(ZipEntry("prefs.json")); z.write(prefsJson.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifestJson.toByteArray()); z.closeEntry()
        }
        return zip to dbBytes
    }

    /** A database of the current schema with one row per table, tagged [name]. */
    private fun currentDbBytes(name: String, extra: (SQLiteDatabase) -> Unit = {}): ByteArray {
        val file = BackupFixtures.createCurrentDb(context, "archive-$name.db")
        BackupFixtures.withDb(file) { db ->
            BackupFixtures.seedCurrent(db, name, 1L)
            extra(db)
        }
        return file.readBytes().also { BackupDatabaseFiles.deleteWithSideFiles(file) }
    }

    private val liveBytes = "live database".toByteArray()

    /** The file a refused restore must leave as it was. */
    private fun liveDbFile() = context.getDatabasePath("bydmate.db").apply {
        parentFile?.mkdirs()
        writeBytes(liveBytes)
    }

    private fun assertRefusedAndUntouched(zip: File, message: String, live: File) {
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            BackupManager(context, appDatabase, AppStrings(context), listOf("automation")).restore(zip)
        }
        assertEquals(message, error.message)
        assertArrayEquals(liveBytes, live.readBytes())
        val state = context.getSharedPreferences(PostRestoreCheck.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(state.getBoolean(PostRestoreCheck.KEY_PENDING, false))
        assertTrue(live.parentFile!!.list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `full archive with a foreign schema is refused, live db untouched`() {
        val live = liveDbFile()
        val foreign = tmp.newFile("foreign.db").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use { db ->
            db.execSQL("CREATE TABLE t (x TEXT)")
            db.execSQL("INSERT INTO t VALUES ('x')")
            // The version agrees with the manifest: only the tables give it away.
            db.version = AppDatabase.SCHEMA_VERSION
        }
        val (zip, _) = backupZip(dbBytes = foreign.readBytes())

        assertRefusedAndUntouched(zip, "Файл базы данных в бэкапе не является базой BYDMate", live)
    }

    @Test
    fun `full archive with an orphan charge_points row is refused`() {
        val live = liveDbFile()
        val orphan = currentDbBytes("orphan") { db ->
            db.execSQL("INSERT INTO charge_points (charge_id, timestamp) VALUES (99, 2002)")
        }
        val (zip, _) = backupZip(dbBytes = orphan)

        assertRefusedAndUntouched(zip, "Файл бэкапа повреждён: нарушены связи между таблицами", live)
    }

    @Test
    fun `restore from file swaps db and applies prefs`() {
        val prefs = context.getSharedPreferences("automation", Context.MODE_PRIVATE)
        prefs.edit().putString("stale_key", "old").commit()
        val (zip, dbBytes) = backupZip()

        BackupManager(context, appDatabase, AppStrings(context), listOf("automation")).restore(zip)

        assertArrayEquals(dbBytes, context.getDatabasePath("bydmate.db").readBytes())
        assertEquals("yes", prefs.getString("restored_key", null))
        assertTrue(!prefs.contains("stale_key"))
    }

    @Test
    fun `restore marks the next start as post-restore`() {
        val (zip, _) = backupZip()

        BackupManager(context, appDatabase, AppStrings(context), listOf("automation")).restore(zip)

        val state = context.getSharedPreferences(PostRestoreCheck.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(state.getBoolean(PostRestoreCheck.KEY_PENDING, false))
        assertTrue(state.getLong(PostRestoreCheck.KEY_TS, 0L) > 0L)
    }

    @Test
    fun `a second restore waits until the first one has swapped its database in`() {
        val (first, _) = backupZip(name = "first")
        val (second, secondBytes) = backupZip(name = "second")
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        // The first restore stops inside its swap, with its temp file not yet renamed.
        every { appDatabase.close() } answers {
            if (closes.incrementAndGet() == 1) {
                closing.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val manager = BackupManager(context, appDatabase, AppStrings(context), listOf("automation"))
        val errors = java.util.concurrent.ConcurrentHashMap<String, Throwable>()
        val a = thread { runCatching { manager.restore(first) }.onFailure { errors["first"] = it } }
        assertTrue(closing.await(5, TimeUnit.SECONDS))
        val b = thread { runCatching { manager.restore(second) }.onFailure { errors["second"] = it } }
        val deadline = System.currentTimeMillis() + 5_000
        while (b.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.sleep(10)

        release.countDown()
        a.join(5_000)
        b.join(5_000)

        assertEquals(emptyMap<String, Throwable>(), errors.toMap())
        assertArrayEquals(secondBytes, context.getDatabasePath("bydmate.db").readBytes())
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun restoreWithProductionLists(zip: File) =
        BackupManager(context, appDatabase, AppStrings(context), BackupManager.PREFS_FILES, BackupManager.EXCLUDED_PREFS_KEYS).restore(zip)

    @Test
    fun `old backup without the new prefs files restores and leaves them alone`() {
        prefs(AdbRestorePreferencesImpl.PREFS_NAME).edit().putBoolean(AdbRestorePreferencesImpl.KEY_ENABLED, true).commit()
        prefs(BlindSpotPreferences.PREFS_NAME).edit().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()
        prefs(SplitPreferencesImpl.PREFS_NAME).edit().putBoolean(SplitPreferencesImpl.KEY_FEATURE_ENABLED, true).commit()
        prefs(HudController.PREFS_NAME).edit().putBoolean(HudController.KEY_ENABLED, true).commit()
        val (zip, _) = backupZip()

        restoreWithProductionLists(zip)

        assertEquals("yes", prefs("automation").getString("restored_key", null))
        assertTrue(AdbRestorePreferencesImpl(context).isEnabled())
        assertTrue(BlindSpotPreferences(context).enabled)
        assertTrue(SplitPreferencesImpl(context).isFeatureEnabled())
        assertTrue(prefs(HudController.PREFS_NAME).getBoolean(HudController.KEY_ENABLED, false))
    }

    @Test
    fun `legacy prefs file absent from the backup is cleared, per-device keys stay`() {
        prefs("bydmate_widget").edit().putBoolean("widget_enabled", true).commit()
        prefs("cluster_projection").edit().putInt("last_vd_id", 5).putBoolean("mirror_enabled", true).commit()
        // Older exports skipped empty files, so "absent" meant "empty" for these.
        val (zip, _) = backupZip()

        restoreWithProductionLists(zip)

        assertTrue(prefs("bydmate_widget").all.isEmpty())
        assertEquals(mapOf("last_vd_id" to 5), prefs("cluster_projection").all)
    }

    @Test
    fun `explicit empty prefs file in the backup is cleared`() {
        prefs("bydmate_widget").edit().putBoolean("widget_enabled", true).commit()
        prefs(BlindSpotPreferences.PREFS_NAME).edit().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()
        val (zip, _) = backupZip(
            mapOf("bydmate_widget" to emptyMap(), BlindSpotPreferences.PREFS_NAME to emptyMap()),
        )

        restoreWithProductionLists(zip)

        assertTrue(prefs("bydmate_widget").all.isEmpty())
        assertFalse(BlindSpotPreferences(context).enabled)
    }

    @Test
    fun `restore keeps the local ADB write cooldown`() {
        AdbRestorePreferencesImpl(context).recordWrite("local-wifi", 5L)
        val (zip, _) = backupZip(
            mapOf(
                AdbRestorePreferencesImpl.PREFS_NAME to mapOf(
                    AdbRestorePreferencesImpl.KEY_ENABLED to true,
                    AdbRestorePreferencesImpl.KEY_LAST_WRITE_NETWORK to "other-wifi",
                    AdbRestorePreferencesImpl.KEY_LAST_WRITE_AT to 9L,
                ),
            ),
        )

        restoreWithProductionLists(zip)

        val adb = AdbRestorePreferencesImpl(context)
        assertTrue(adb.isEnabled())
        assertEquals("local-wifi", adb.lastWriteNetwork())
        assertEquals(5L, adb.lastWriteAtMs())
    }

    @Test
    fun `restore keeps per-device keys of this car`() {
        val cluster = context.getSharedPreferences("cluster_projection", Context.MODE_PRIVATE)
        cluster.edit().putInt("last_vd_id", 5).putInt("split_ff_seen_boot", 3).putBoolean("mirror_enabled", false).commit()
        val hud = context.getSharedPreferences("hud", Context.MODE_PRIVATE)
        hud.edit().putBoolean("hud_supported", false).commit()
        // A pre-change backup still carries the other car's runtime markers.
        val (zip, _) = backupZip(
            mapOf(
                "cluster_projection" to mapOf("last_vd_id" to 9, "split_ff_seen_boot" to 8, "mirror_enabled" to true),
                "hud" to mapOf("hud_supported" to true, "hud_enabled" to true),
            )
        )

        restoreWithProductionLists(zip)

        assertEquals(5, cluster.getInt("last_vd_id", -1))
        assertEquals(3, cluster.getInt("split_ff_seen_boot", -1))
        assertTrue(cluster.getBoolean("mirror_enabled", false))
        assertTrue(!hud.getBoolean("hud_supported", true))
        assertTrue(hud.getBoolean("hud_enabled", false))
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

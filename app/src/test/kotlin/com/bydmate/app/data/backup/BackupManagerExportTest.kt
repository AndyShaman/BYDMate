package com.bydmate.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackupManagerExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appDatabase = mockk<AppDatabase>(relaxed = true)
    private val supportDb = mockk<SupportSQLiteDatabase>(relaxed = true)
    private lateinit var liveDb: File

    private fun checkpointCursor(busy: Int) = BackupFixtures.checkpointCursor(busy)

    private fun manager() = BackupManager(context, appDatabase, AppStrings(context), listOf("automation"))

    @Before
    fun setUp() {
        every { appDatabase.openHelper.writableDatabase } returns supportDb
        liveDb = BackupFixtures.createCurrentDb(context, "bydmate.db")
        BackupFixtures.withDb(liveDb) { BackupFixtures.seedCurrent(it, "live", 1L) }
    }

    private fun <T> entryDb(zip: File, name: String, block: (SQLiteDatabase) -> T): T =
        BackupFixtures.withDb(BackupFixtures.openEntryDb(zip, name, tmp.root), block)

    private fun manifestParts(zip: File): Set<BackupPart> = BackupManager.manifestParts(
        JSONObject(BackupFixtures.entryBytes(zip, "manifest.json")!!.decodeToString())
    )

    @Test
    fun `export fails when checkpoint stays busy`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 1) }
        assertThrows(IllegalStateException::class.java) { manager().export() }
    }

    @Test
    fun `export fails when wal grows back after checkpoint`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        // Non-empty WAL after a "successful" TRUNCATE = a writer slipped in.
        File(context.getDatabasePath("bydmate.db").parentFile, "bydmate.db-wal").writeBytes(ByteArray(32))
        assertThrows(IllegalStateException::class.java) { manager().export() }
    }

    @Test
    fun `full export keeps every part and empties runtime state`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }

        val zip = manager().export(BackupPart.ALL)

        assertEquals(BackupPart.ALL, manifestParts(zip))
        assertNull(BackupFixtures.entryBytes(zip, "bydmate.part.db"))
        entryDb(zip, "bydmate.db") { db ->
            for (table in BackupParts.TABLES_TABLES + BackupParts.SETTINGS_TABLES) {
                assertEquals(table, 1, BackupFixtures.count(db, table))
            }
            for (table in BackupParts.RUNTIME_TABLES) {
                assertEquals(table, 0, BackupFixtures.count(db, table))
            }
            assertEquals("live", BackupFixtures.setting(db, "currency"))
            assertEquals("live", BackupFixtures.setting(db, "trip1_reset_ts"))
            assertEquals(BackupFixtures.TOKEN, BackupFixtures.setting(db, "tg_backup_token"))
            assertNull(BackupFixtures.setting(db, "last_known_soc"))
        }
        // The live database keeps its runtime state: only the copy is trimmed.
        BackupFixtures.withDb(liveDb) { db ->
            assertEquals(1, BackupFixtures.count(db, "last_state"))
            assertEquals("live", BackupFixtures.setting(db, "last_known_soc"))
        }
    }

    @Test
    fun `partial export writes bydmate part db with only the chosen parts`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        context.getSharedPreferences("automation", Context.MODE_PRIVATE).edit().putString("pref", "x").commit()

        val zip = manager().export(setOf(BackupPart.TABLES))

        assertEquals(setOf(BackupPart.TABLES), manifestParts(zip))
        assertNull(BackupFixtures.entryBytes(zip, "bydmate.db"))
        assertEquals("{}", BackupFixtures.entryBytes(zip, "prefs.json")!!.decodeToString())
        entryDb(zip, "bydmate.part.db") { db ->
            for (table in BackupParts.TABLES_TABLES) assertEquals(table, 1, BackupFixtures.count(db, table))
            for (table in BackupParts.SETTINGS_TABLES + BackupParts.RUNTIME_TABLES) {
                assertEquals(table, 0, BackupFixtures.count(db, table))
            }
            assertEquals("live", BackupFixtures.setting(db, "trip1_reset_ts"))
            assertNull(BackupFixtures.setting(db, "currency"))
            assertNull(BackupFixtures.setting(db, "tg_backup_token"))
            assertNull(BackupFixtures.setting(db, "last_known_soc"))
        }
    }

    @Test
    fun `keys only export carries the keys and nothing else`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }

        val zip = manager().export(setOf(BackupPart.KEYS))

        entryDb(zip, "bydmate.part.db") { db ->
            for (table in BackupParts.TABLES_TABLES + BackupParts.SETTINGS_TABLES) {
                assertEquals(table, 0, BackupFixtures.count(db, table))
            }
            assertEquals(BackupFixtures.TOKEN, BackupFixtures.setting(db, "tg_backup_token"))
            assertNull(BackupFixtures.setting(db, "currency"))
            assertNull(BackupFixtures.setting(db, "trip1_reset_ts"))
        }
    }

    @Test
    fun `archive without keys holds no trace of the token in its raw bytes`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        val token = BackupFixtures.TOKEN.toByteArray()

        val withKeys = manager().export(BackupPart.ALL)
        val withoutKeys = manager().export(setOf(BackupPart.TABLES, BackupPart.SETTINGS))

        // The check finds the token where it is: the raw database file of the full archive.
        assertTrue(BackupFixtures.entryBytes(withKeys, "bydmate.db")!!.containsSequence(token))
        // Raw file bytes, not a query: a deleted row left in a free page would still be found here.
        assertFalse(BackupFixtures.entryBytes(withoutKeys, "bydmate.part.db")!!.containsSequence(token))
        assertFalse(withoutKeys.readBytes().containsSequence(token))
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    @Test
    fun `old reader of v3 17 5 rejects a partial archive`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        val zip = manager().export(setOf(BackupPart.TABLES, BackupPart.SETTINGS))

        assertThrows(IllegalStateException::class.java) {
            zip.inputStream().use { BackupManager.readBackupEntries(it, AppStrings(context), dbEntryNames = setOf("bydmate.db")) }
        }
        val entries = zip.inputStream().use { BackupManager.readBackupEntries(it, AppStrings(context)) }
        assertTrue(entries.partial)
    }

    /** Base names export() may pick for the next few seconds, so a test crossing a second still collides. */
    private fun upcomingNames(prefix: String): List<File> {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val now = System.currentTimeMillis()
        return (0..2).map { File(dir, prefix + fmt.format(Date(now + it * 1000L)) + ".zip") }
    }

    @Test
    fun `export carries the new prefs files and drops per-device keys`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs(AdbRestorePreferencesImpl.PREFS_NAME).edit()
            .putBoolean(AdbRestorePreferencesImpl.KEY_ENABLED, true)
            .putString(AdbRestorePreferencesImpl.KEY_LAST_WRITE_NETWORK, "home-wifi")
            .putLong(AdbRestorePreferencesImpl.KEY_LAST_WRITE_AT, 123L)
            .commit()
        prefs(BlindSpotPreferences.PREFS_NAME).edit().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()
        prefs(SplitPreferencesImpl.PREFS_NAME).edit().putBoolean(SplitPreferencesImpl.KEY_FEATURE_ENABLED, true).commit()
        prefs(HudController.PREFS_NAME).edit()
            .putBoolean(HudController.KEY_ENABLED, true).putBoolean(HudController.KEY_SUPPORTED, true).commit()
        prefs("automation").edit()
            .putBoolean("auto_enabled", true)
            .putString("service_start_boot_id", "boot-a")
            .putLong("service_start_last_seen_elapsed", 1L)
            .putLong("service_start_last_seen_uptime", 1L)
            .commit()
        val runtimeKeys = listOf(
            "last_vd_id", "direct_display_id", "compositor_powered_on", "freeform_reboot_pending",
            "a11y_recovery_last_elapsed_ms", "a11y_recovery_fail_streak",
            "cluster_direct_forced", "ui7_frame_saved_center", "split_ff_seen_boot",
            "direct_density_unsafe_ru.dublgis.dgismobile", "direct_density_unsafe_version",
        )
        prefs("cluster_projection").edit().putBoolean("mirror_enabled", true)
            .apply { runtimeKeys.forEach { putInt(it, 7) } }.commit()

        val zip = BackupManager(
            context, appDatabase, AppStrings(context), BackupManager.PREFS_FILES, BackupManager.EXCLUDED_PREFS_KEYS,
        ).export()

        val exported = ZipFile(zip).use { z ->
            BackupManager.deserializePrefs(z.getInputStream(z.getEntry("prefs.json")).readBytes().decodeToString())
        }
        // The ADB write cooldown stays behind: only the toggle travels.
        assertEquals(mapOf(AdbRestorePreferencesImpl.KEY_ENABLED to true), exported[AdbRestorePreferencesImpl.PREFS_NAME])
        assertEquals(true, exported[BlindSpotPreferences.PREFS_NAME]?.get(BlindSpotPreferences.KEY_ENABLED))
        assertEquals(true, exported[SplitPreferencesImpl.PREFS_NAME]?.get(SplitPreferencesImpl.KEY_FEATURE_ENABLED))
        assertEquals(mapOf(HudController.KEY_ENABLED to true), exported[HudController.PREFS_NAME])
        assertEquals(mapOf("mirror_enabled" to true), exported["cluster_projection"])
        // The service_start session of this boot stays on this head unit (#177).
        assertEquals(mapOf("auto_enabled" to true), exported["automation"])
        // Empty files are written too: restore reads "absent" as "older backup".
        assertTrue(exported.keys.containsAll(BackupManager.PREFS_FILES))
    }

    @Test
    fun `export clears when each rule last fired and keeps how often`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        BackupFixtures.withDb(liveDb) { it.execSQL("UPDATE automation_rules SET last_triggered_at = 123, trigger_count = 5") }

        val zip = manager().export(BackupPart.ALL)

        entryDb(zip, "bydmate.db") { db ->
            assertNull(BackupFixtures.text(db, "SELECT last_triggered_at FROM automation_rules"))
            assertEquals("5", BackupFixtures.text(db, "SELECT trigger_count FROM automation_rules"))
        }
    }

    @Test
    fun `export leaves the voice ducking marker behind`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
            .putInt("pre_duck_volume", 3).putBoolean("voice_enabled", true).commit()

        val zip = BackupManager(
            context, appDatabase, AppStrings(context), BackupManager.PREFS_FILES, BackupManager.EXCLUDED_PREFS_KEYS,
        ).export()

        val exported = ZipFile(zip).use { z ->
            BackupManager.deserializePrefs(z.getInputStream(z.getEntry("prefs.json")).readBytes().decodeToString())
        }
        assertEquals(mapOf("voice_enabled" to true), exported["voice"])
    }

    @Test
    fun `two exports in the same second give two different files`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        val first = manager().export()
        val firstBytes = first.readBytes()
        upcomingNames("bydmate_backup_").forEach { if (!it.exists()) it.writeBytes(byteArrayOf(9)) }

        val second = manager().export()

        assertNotEquals(first.absolutePath, second.absolutePath)
        assertTrue(second.name, second.name.startsWith("bydmate_backup_") && second.name.endsWith("_2.zip"))
        assertArrayEquals(firstBytes, first.readBytes())
    }

    @Test
    fun `export does not reuse a name the auto runner already renamed`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        val autoCopies = upcomingNames("bydmate_backup_auto_").onEach { it.writeBytes(byteArrayOf(9)) }

        val exported = manager().export()

        assertTrue(exported.name, exported.name.endsWith("_2.zip"))
        autoCopies.forEach { assertEquals(1L, it.length()) }
    }
}

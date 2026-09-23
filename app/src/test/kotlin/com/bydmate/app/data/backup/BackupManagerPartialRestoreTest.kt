package com.bydmate.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.data.backup.BackupFixtures.count
import com.bydmate.app.data.backup.BackupFixtures.setting
import com.bydmate.app.data.backup.BackupFixtures.text
import com.bydmate.app.data.local.database.AppDatabase
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** #238: a subset of parts is merged into the live database instead of replacing the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackupManagerPartialRestoreTest {

    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appDatabase = mockk<AppDatabase>(relaxed = true)
    private lateinit var liveDb: File

    private fun manager() =
        BackupManager(context, appDatabase, BackupManager.PREFS_FILES, BackupManager.EXCLUDED_PREFS_KEYS)

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        every { appDatabase.openHelper.writableDatabase } returns mockk<SupportSQLiteDatabase>(relaxed = true)
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { BackupFixtures.checkpointCursor() }
        liveDb = BackupFixtures.createCurrentDb(context, "bydmate.db")
        BackupFixtures.withDb(liveDb) { BackupFixtures.seedCurrent(it, "live", 1L, token = "live-token") }
        prefs("automation").edit().putString("pref", "live").commit()
    }

    /** Current-schema archive database with the "archive" rows (ids 7), trimmed to [parts] like an export. */
    private fun archiveDbBytes(parts: Set<BackupPart> = BackupPart.ALL): ByteArray {
        val file = BackupFixtures.createCurrentDb(context, "archive-src.db")
        BackupFixtures.withDb(file) { db ->
            BackupFixtures.seedCurrent(db, "archive", 7L, token = "archive-token")
            BackupParts.RUNTIME_TABLES.forEach { db.execSQL("DELETE FROM `$it`") }
            for (part in BackupPart.entries - parts) {
                BackupParts.tablesOf(part).forEach { db.execSQL("DELETE FROM `$it`") }
                val (where, args) = BackupParts.settingsKeyFilter(part)
                db.delete("settings", where, args)
            }
        }
        return file.readBytes()
    }

    private val archivePrefs = mapOf("automation" to mapOf<String, Any?>("pref" to "archive"))

    private fun <T> live(block: (SQLiteDatabase) -> T): T = BackupFixtures.withDb(liveDb, block)

    @Test
    fun `tables only archive replaces the history and keeps rules, places, keys and prefs`() {
        val zip = BackupFixtures.archive(archiveDbBytes(setOf(BackupPart.TABLES)), setOf(BackupPart.TABLES))

        manager().restore(zip, BackupPart.ALL)

        live { db ->
            for (table in BackupParts.TABLES_TABLES) assertEquals(table, 1, count(db, table))
            assertEquals("archive", text(db, "SELECT source FROM trips WHERE id = 7"))
            assertEquals(7L, text(db, "SELECT trip_id FROM trip_points")!!.toLong())
            assertEquals("archive", text(db, "SELECT detection_source FROM charges WHERE id = 7"))
            assertEquals("archive", text(db, "SELECT trip_rule FROM tariff_periods"))
            assertEquals("archive", setting(db, "trip1_reset_ts"))
            // Everything outside Tables is the live one.
            assertEquals("live", text(db, "SELECT name FROM automation_rules"))
            assertEquals("live", text(db, "SELECT name FROM places"))
            assertEquals("live", setting(db, "currency"))
            assertEquals("live-token", setting(db, "tg_backup_token"))
            // Runtime state of this head unit stays, the open trip no longer points into old history.
            assertEquals("live", setting(db, "last_known_soc"))
            assertEquals(1, count(db, "automation_log"))
            assertEquals(1, count(db, "last_state"))
            assertNull(text(db, "SELECT open_trip_id FROM last_state"))
        }
        assertEquals("live", prefs("automation").getString("pref", null))
        assertTrue(prefs(PostRestoreCheck.PREFS_NAME).getBoolean(PostRestoreCheck.KEY_PENDING, false))
        assertTrue(context.getDatabasePath("bydmate.db").parentFile!!.list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `settings chosen from a full archive replace settings and prefs only`() {
        val zip = BackupFixtures.archive(archiveDbBytes(), BackupPart.ALL, prefs = archivePrefs)

        manager().restore(zip, setOf(BackupPart.SETTINGS))

        live { db ->
            assertEquals("archive", text(db, "SELECT name FROM automation_rules"))
            assertEquals("archive", text(db, "SELECT name FROM places"))
            assertEquals("archive", setting(db, "currency"))
            assertEquals("live", text(db, "SELECT source FROM trips"))
            assertEquals("live", setting(db, "trip1_reset_ts"))
            assertEquals("live-token", setting(db, "tg_backup_token"))
            assertEquals("live", setting(db, "last_known_soc"))
            // History untouched: the open trip still points at the live one.
            assertEquals(1L, text(db, "SELECT open_trip_id FROM last_state")!!.toLong())
        }
        assertEquals("archive", prefs("automation").getString("pref", null))
    }

    @Test
    fun `keys chosen from a full archive replace the keys only`() {
        val zip = BackupFixtures.archive(archiveDbBytes(), BackupPart.ALL, prefs = archivePrefs)

        manager().restore(zip, setOf(BackupPart.KEYS))

        live { db ->
            assertEquals("archive-token", setting(db, "tg_backup_token"))
            assertEquals("live", setting(db, "currency"))
            assertEquals("live", text(db, "SELECT source FROM trips"))
        }
        assertEquals("live", prefs("automation").getString("pref", null))
    }

    @Test
    fun `archive of an older schema is migrated before the merge`() {
        migrationHelper.createDatabase("archive-19.db", 19).use { db ->
            db.execSQL("INSERT INTO trips (id, start_ts, source) VALUES (7, 1000, 'archive')")
            db.execSQL(
                "INSERT INTO charges (id, start_ts, status, merged_count, detection_source) " +
                    "VALUES (7, 2000, 'COMPLETED', 0, 'archive')"
            )
            db.execSQL("INSERT INTO settings (key, value) VALUES ('home_tariff', '0.31')")
        }
        val bytes = context.getDatabasePath("archive-19.db").readBytes()
        // A v3.17.5 archive: no parts in the manifest, so a full one.
        val zip = BackupFixtures.archive(bytes, parts = null, schema = 19)

        manager().restore(zip, setOf(BackupPart.TABLES))

        live { db ->
            assertEquals(1, count(db, "trips"))
            assertEquals("archive", text(db, "SELECT source FROM trips WHERE id = 7"))
            assertNull(text(db, "SELECT meter_kwh FROM charges WHERE id = 7"))
            // MIGRATION_19_20 seeded the first tariff period from the archive's own tariff.
            assertEquals("0.31", text(db, "SELECT home_rate FROM tariff_periods"))
            // Settings were not chosen: the live tariff and rules stay.
            assertEquals("live", text(db, "SELECT name FROM automation_rules"))
            assertNull(setting(db, "home_tariff"))
        }
    }

    @Test
    fun `a part the archive does not carry is refused and the live database is untouched`() {
        val zip = BackupFixtures.archive(archiveDbBytes(setOf(BackupPart.TABLES)), setOf(BackupPart.TABLES))

        assertThrows(IllegalStateException::class.java) { manager().restore(zip, setOf(BackupPart.KEYS)) }

        live { db -> assertEquals("live", text(db, "SELECT source FROM trips")) }
        assertTrue(!prefs(PostRestoreCheck.PREFS_NAME).getBoolean(PostRestoreCheck.KEY_PENDING, false))
    }

    @Test
    fun `trimmed database posing as a full archive is refused`() {
        val zip = BackupFixtures.archive(archiveDbBytes(setOf(BackupPart.TABLES)), setOf(BackupPart.TABLES), dbEntry = "bydmate.db")

        assertThrows(IllegalStateException::class.java) { manager().restore(zip, BackupPart.ALL) }

        live { db -> assertEquals("live", text(db, "SELECT name FROM automation_rules")) }
    }

    @Test
    fun `archiveParts reads the manifest, an old archive counts as full`() {
        val partial = BackupFixtures.archive(byteArrayOf(1), setOf(BackupPart.TABLES, BackupPart.KEYS))
        val old = BackupFixtures.archive(byteArrayOf(1), parts = null)

        assertEquals(setOf(BackupPart.TABLES, BackupPart.KEYS), manager().archiveParts(partial))
        assertEquals(BackupPart.ALL, manager().archiveParts(old))
    }
}

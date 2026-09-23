package com.bydmate.app.data.backup

import android.content.Context
import android.database.MatrixCursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
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

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appDatabase = mockk<AppDatabase>(relaxed = true)
    private val supportDb = mockk<SupportSQLiteDatabase>(relaxed = true)

    // Fresh cursor per call: export() closes it via .use on every retry attempt.
    private fun checkpointCursor(busy: Int) =
        MatrixCursor(arrayOf("busy", "log", "checkpointed")).apply { addRow(arrayOf(busy, 0, 0)) }

    private fun manager() = BackupManager(context, appDatabase, listOf("automation"))

    @Before
    fun setUp() {
        every { appDatabase.openHelper.writableDatabase } returns supportDb
        val dbFile = context.getDatabasePath("bydmate.db")
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(byteArrayOf(1, 2, 3))
    }

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
    fun `export packs exact db bytes when checkpoint is clean`() {
        every { appDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)", null) } answers { checkpointCursor(busy = 0) }
        val zip = manager().export()
        ZipFile(zip).use { z ->
            val entry = z.getEntry("bydmate.db")
            assertArrayEquals(byteArrayOf(1, 2, 3), z.getInputStream(entry).readBytes())
        }
    }

    /** Base names export() may pick for the next few seconds, so a test crossing a second still collides. */
    private fun upcomingNames(prefix: String): List<File> {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val now = System.currentTimeMillis()
        return (0..2).map { File(dir, prefix + fmt.format(Date(now + it * 1000L)) + ".zip") }
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

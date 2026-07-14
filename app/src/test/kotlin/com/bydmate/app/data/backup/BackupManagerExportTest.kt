package com.bydmate.app.data.backup

import android.content.Context
import android.database.MatrixCursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
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
}

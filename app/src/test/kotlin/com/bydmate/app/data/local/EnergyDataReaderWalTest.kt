package com.bydmate.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// NOTE: Robolectric's SQLite does not create on-disk -wal sidecar files even when
// journal_mode=WAL is set (confirmed: File("EC_database.db-wal").length() == 0 after WAL
// insert — fallback path from the task brief is therefore used):
//   • fingerprint test: simulate WAL growth by appending bytes to a hand-made sidecar
//   • read test: call copyToLocal() directly (internal) to verify sidecar copying without
//     going through readTripsFromDb, which would let SQLite repair/truncate the fake WAL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EnergyDataReaderWalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var reader: EnergyDataReader

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "energydata-wal-test").apply { deleteRecursively(); mkdirs() }
        reader = object : EnergyDataReader(context) {
            override fun energyDataDir(): File = dir
        }
    }

    /** Creates and closes a source DB with one trip row (fully checkpointed). */
    private fun createSourceDb(): File {
        val dbFile = File(dir, "EC_database.db")
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
            """CREATE TABLE EnergyConsumption (
                _id INTEGER PRIMARY KEY, start_timestamp INTEGER, end_timestamp INTEGER,
                duration INTEGER, trip REAL, electricity REAL, is_deleted INTEGER DEFAULT 0)"""
        )
        db.execSQL("INSERT INTO EnergyConsumption VALUES (1, 1000, 1600, 600, 5.0, 1.2, 0)")
        db.close() // closed = fully checkpointed in any journal mode
        return dbFile
    }

    // Fingerprint test — kept as required by the brief. Uses a hand-made WAL sidecar because
    // Robolectric does not materialise on-disk WAL frames after PRAGMA journal_mode=WAL.
    @Test
    fun `peekSourceChanged fires on wal-only growth`() {
        createSourceDb()

        reader.markSourceProcessed(context)
        assertFalse("no change right after mark", reader.peekSourceChanged(context))

        // Simulate WAL-only growth: main file is not touched, only the sidecar grows.
        File(dir, "EC_database.db-wal").appendBytes(ByteArray(64))
        assertTrue("wal sidecar growth must be detected", reader.peekSourceChanged(context))
    }

    // Read test — fallback variant: assert that copyToLocal physically copies WAL/SHM sidecars
    // alongside the main DB. Called directly (copyToLocal is internal) so SQLite does not get
    // a chance to repair/truncate the intentionally-fake WAL bytes before the assertion.
    @Test
    fun `copyToLocal brings wal and shm sidecars along`() {
        val sourceDb = createSourceDb()
        // Simulate WAL and SHM sidecars present next to the source DB.
        File(dir, "EC_database.db-wal").writeBytes(ByteArray(64))
        File(dir, "EC_database.db-shm").writeBytes(ByteArray(32))

        val localDb = reader.copyToLocal(sourceDb)

        assertTrue("local db was created", localDb.exists())
        assertTrue(
            "wal sidecar must be copied locally",
            File(localDb.parentFile, localDb.name + "-wal").exists()
        )
        assertTrue(
            "shm sidecar must be copied locally",
            File(localDb.parentFile, localDb.name + "-shm").exists()
        )
    }
}

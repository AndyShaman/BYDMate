package com.bydmate.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.spyk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EnergyDataReaderAvailabilityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun readerWithDir(dir: File): EnergyDataReader {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val spy = spyk(EnergyDataReader(ctx))
        every { spy.energyDataDir() } returns dir
        return spy
    }

    @Test fun `missing directory returns false`() {
        assertFalse(readerWithDir(File(tmp.root, "absent")).isAvailable())
    }

    @Test fun `empty directory returns false`() {
        val dir = tmp.newFolder("energydata")
        assertFalse(readerWithDir(dir).isAvailable())
    }

    @Test fun `directory with EC_database_db returns true`() {
        val dir = tmp.newFolder("energydata")
        File(dir, "EC_database.db").writeBytes(
            "SQLite format 3 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(100)
        )
        assertTrue(readerWithDir(dir).isAvailable())
    }

    @Test fun `directory with arbitrary db file (listFiles path) returns true`() {
        val dir = tmp.newFolder("energydata")
        File(dir, "random_name.db").writeBytes(
            "SQLite format 3 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(100)
        )
        assertTrue(readerWithDir(dir).isAvailable())
    }
}

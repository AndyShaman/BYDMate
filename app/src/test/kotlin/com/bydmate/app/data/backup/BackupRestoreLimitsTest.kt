package com.bydmate.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupRestoreLimitsTest {

    /**
     * Builds raw ZIP bytes that allow duplicate entry names.
     * ZipOutputStream refuses to write duplicates (since JDK 8), but ZipInputStream
     * can read them — exactly the attack surface readBackupEntries must guard against.
     * Uses the STORED (no compression) method to keep the writer simple.
     */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val localHeaders = ByteArrayOutputStream()
        val centralDir = ByteArrayOutputStream()
        val crc32 = java.util.zip.CRC32()
        var localOffset = 0
        var count = 0

        for ((name, bytes) in entries) {
            val nameB = name.toByteArray(Charsets.UTF_8)
            crc32.reset()
            crc32.update(bytes)
            val crc = crc32.value.toInt()
            val size = bytes.size

            // Local file header (30 bytes fixed + name)
            localHeaders.le4(0x04034b50)  // signature
            localHeaders.le2(20)           // version needed
            localHeaders.le2(0)            // flags
            localHeaders.le2(0)            // STORED
            localHeaders.le2(0)            // last mod time
            localHeaders.le2(0)            // last mod date
            localHeaders.le4(crc)          // CRC-32
            localHeaders.le4(size)         // compressed size
            localHeaders.le4(size)         // uncompressed size
            localHeaders.le2(nameB.size)   // name length
            localHeaders.le2(0)            // extra length
            localHeaders.write(nameB)
            localHeaders.write(bytes)

            // Central directory entry (46 bytes fixed + name)
            centralDir.le4(0x02014b50)    // signature
            centralDir.le2(20)             // version made by
            centralDir.le2(20)             // version needed
            centralDir.le2(0)              // flags
            centralDir.le2(0)              // STORED
            centralDir.le2(0)              // last mod time
            centralDir.le2(0)              // last mod date
            centralDir.le4(crc)            // CRC-32
            centralDir.le4(size)           // compressed size
            centralDir.le4(size)           // uncompressed size
            centralDir.le2(nameB.size)     // name length
            centralDir.le2(0)              // extra length
            centralDir.le2(0)              // comment length
            centralDir.le2(0)              // disk start
            centralDir.le2(0)              // internal attrs
            centralDir.le4(0)              // external attrs
            centralDir.le4(localOffset)    // local header offset
            centralDir.write(nameB)

            localOffset += 30 + nameB.size + size
            count++
        }

        val cdBytes = centralDir.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(localHeaders.toByteArray())
        out.write(cdBytes)
        // End of central directory record
        out.le4(0x06054b50)     // signature
        out.le2(0)               // disk number
        out.le2(0)               // start disk
        out.le2(count)           // entries on disk
        out.le2(count)           // total entries
        out.le4(cdBytes.size)    // central dir size
        out.le4(localOffset)     // central dir offset
        out.le2(0)               // comment length
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.le2(v: Int) { write(v and 0xff); write((v ushr 8) and 0xff) }
    private fun ByteArrayOutputStream.le4(v: Int) {
        write(v and 0xff); write((v ushr 8) and 0xff)
        write((v ushr 16) and 0xff); write((v ushr 24) and 0xff)
    }

    private fun validEntries() = arrayOf(
        "bydmate.db" to ByteArray(100) { 7 },
        "prefs.json" to "{}".toByteArray(),
        "manifest.json" to """{"dbSchemaVersion":18}""".toByteArray(),
    )

    @Test
    fun `reads valid backup`() {
        val entries = BackupManager.readBackupEntries(ByteArrayInputStream(zipOf(*validEntries())))
        assertEquals(100, entries.dbBytes.size)
        assertEquals("{}", entries.prefsJson)
        assertEquals("""{"dbSchemaVersion":18}""", entries.manifestJson)
    }

    @Test
    fun `oversized entry fails fast`() {
        val zip = zipOf(
            "bydmate.db" to ByteArray(10_000),
            "prefs.json" to "{}".toByteArray(),
            "manifest.json" to "{}".toByteArray(),
        )
        assertThrows(IllegalStateException::class.java) {
            BackupManager.readBackupEntries(ByteArrayInputStream(zip), maxEntryBytes = 1_000)
        }
    }

    @Test
    fun `total size cap fails`() {
        assertThrows(IllegalStateException::class.java) {
            BackupManager.readBackupEntries(ByteArrayInputStream(zipOf(*validEntries())), maxTotalBytes = 50)
        }
    }

    @Test
    fun `duplicate expected entry fails`() {
        val zip = zipOf("bydmate.db" to ByteArray(10), *validEntries())
        assertThrows(IllegalStateException::class.java) {
            BackupManager.readBackupEntries(ByteArrayInputStream(zip))
        }
    }

    @Test
    fun `missing entry fails`() {
        val zip = zipOf("bydmate.db" to ByteArray(10))
        assertThrows(IllegalStateException::class.java) {
            BackupManager.readBackupEntries(ByteArrayInputStream(zip))
        }
    }

    @Test
    fun `unknown entries are skipped without reading into memory`() {
        // evil.bin is larger than maxEntryBytes but must be skipped, not loaded.
        val zip = zipOf("evil.bin" to ByteArray(10_000), *validEntries())
        val entries = BackupManager.readBackupEntries(ByteArrayInputStream(zip), maxEntryBytes = 1_000)
        assertEquals(100, entries.dbBytes.size)
    }

    @Test
    fun `too many entries fails`() {
        val junk = Array(20) { i -> "junk$i" to ByteArray(1) }
        val zip = zipOf(*junk, *validEntries())
        assertThrows(IllegalStateException::class.java) {
            BackupManager.readBackupEntries(ByteArrayInputStream(zip))
        }
    }
}

package com.bydmate.app.helper.offreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** The daemon's pending power-off reports on disk. */
class PendingReportsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun report(id: String, offMs: Long, text: String = "<b>$id</b>\nЗаряд 64%") = PendingReport(id, 42L, offMs, text)

    @Test fun `a saved report reads back whole, owner-only, with no temp file left`() {
        val dir = File(tmp.root, "offreport")
        val store = PendingReports(dir)
        assertTrue(store.save(report("a1", 1_000L)))
        val back = store.list().single()
        assertEquals("a1", back.id)
        assertEquals(42L, back.chatId)
        assertEquals(1_000L, back.powerOffMs)
        assertEquals("<b>a1</b>\nЗаряд 64%", back.text)
        val file = dir.listFiles()!!.single()
        assertTrue(file.name.endsWith(".rep"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
    }

    @Test fun `at most ten are kept, the oldest power-offs go`() {
        val store = PendingReports(tmp.newFolder())
        (1..12).forEach { store.save(report("r$it", it * 1_000L)) }
        assertEquals(PendingReports.MAX, store.count())
        assertEquals((3..12).map { "r$it" }, store.list().map { it.id })
    }

    @Test fun `delete takes one report, deleteAll the rest`() {
        val store = PendingReports(tmp.newFolder())
        store.save(report("a1", 1_000L))
        store.save(report("a2", 2_000L))
        store.save(report("a3", 3_000L))
        store.delete("a2")
        assertEquals(listOf("a1", "a3"), store.list().map { it.id })
        assertEquals(listOf("a1", "a3"), store.deleteAll())
        assertEquals(0, store.count())
    }

    @Test fun `an unreadable file is dropped and does not block the others`() {
        val dir = tmp.newFolder()
        val store = PendingReports(dir)
        store.save(report("a1", 1_000L))
        File(dir, "5-bad.rep").writeBytes(byteArrayOf(0, 0, 0, 1, 0))
        assertEquals(listOf("a1"), store.list().map { it.id })
        assertEquals(1, store.count())
    }

    @Test fun `an empty or missing folder has nothing pending`() {
        val store = PendingReports(File(tmp.root, "never-made"))
        assertEquals(0, store.count())
        assertTrue(store.list().isEmpty())
        assertTrue(store.deleteAll().isEmpty())
    }
}

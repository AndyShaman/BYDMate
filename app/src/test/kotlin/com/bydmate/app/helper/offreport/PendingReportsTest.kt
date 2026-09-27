package com.bydmate.app.helper.offreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val store = PendingReports(dir, syncDir = {})
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
        val store = PendingReports(tmp.newFolder(), syncDir = {})
        (1..12).forEach { store.save(report("r$it", it * 1_000L)) }
        assertEquals(PendingReports.MAX, store.count())
        assertEquals((3..12).map { "r$it" }, store.list().map { it.id })
    }

    @Test fun `delete takes one report, deleteAll the rest`() {
        val store = PendingReports(tmp.newFolder(), syncDir = {})
        store.save(report("a1", 1_000L))
        store.save(report("a2", 2_000L))
        store.save(report("a3", 3_000L))
        store.delete("2000-a2")
        assertEquals(listOf("a1", "a3"), store.list().map { it.id })
        assertEquals(listOf("a1", "a3"), store.deleteAll().ids)
        assertEquals(0, store.count())
    }

    @Test fun `an event is its key, two power-offs under one report id are two events`() {
        val store = PendingReports(tmp.newFolder(), syncDir = {})
        store.save(report("x1", 1_000L))
        store.save(report("x1", 2_000L))
        assertEquals(listOf("1000-x1", "2000-x1"), store.list().map { it.key })
        store.delete("1000-x1")
        assertEquals(listOf(2_000L), store.list().map { it.powerOffMs })
    }

    @Test fun `delete takes a temp file a failed save left too, so nothing is recovered later`() {
        val dir = tmp.newFolder()
        val store = PendingReports(dir, syncDir = {})
        store.save(report("a1", 1_000L))
        File(dir, "1000-a1.tmp").writeBytes(File(dir, "1000-a1.rep").readBytes())
        store.delete("1000-a1")
        assertTrue(dir.list()!!.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test fun `an unreadable file is dropped and does not block the others`() {
        val dir = tmp.newFolder()
        val store = PendingReports(dir, syncDir = {})
        store.save(report("a1", 1_000L))
        File(dir, "5-bad.rep").writeBytes(byteArrayOf(0, 0, 0, 1, 0))
        assertEquals(listOf("a1"), store.list().map { it.id })
        assertEquals(1, store.count())
    }

    @Test fun `an empty or missing folder has nothing pending`() {
        val store = PendingReports(File(tmp.root, "never-made"), syncDir = {})
        assertEquals(0, store.count())
        assertTrue(store.list().isEmpty())
        val cleared = store.deleteAll()
        assertTrue(cleared.ids.isEmpty())
        assertTrue(cleared.complete)
    }

    @Test fun `a save the caller no longer wants writes nothing`() {
        val dir = tmp.newFolder()
        val store = PendingReports(dir, syncDir = {})
        assertFalse(store.save(report("a1", 1_000L)) { false })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun `deleteAll takes temp files too and says when a file would not go`() {
        val dir = tmp.newFolder()
        val store = PendingReports(dir, syncDir = {})
        store.save(report("a1", 1_000L))
        File(dir, "2-cut.tmp").writeBytes(byteArrayOf(0, 0, 0, 1))
        assertTrue(store.deleteAll().complete)
        assertTrue(dir.listFiles()!!.isEmpty())

        store.save(report("a2", 2_000L))
        val stuck = File(dir, "3-stuck.rep").apply { mkdirs() } // a non-empty directory: delete() fails
        File(stuck, "x").writeText("x")
        val cleared = store.deleteAll()
        assertFalse(cleared.complete)
        assertEquals(listOf("a2"), cleared.ids)
        assertEquals(0, store.count())
    }

    @Test fun `a whole temp file left by a dead daemon is recovered, a cut or padded one is deleted`() {
        val dir = tmp.newFolder()
        val store = PendingReports(dir, syncDir = {})
        store.save(report("a1", 1_000L))
        val bytes = File(dir, "1000-a1.rep").readBytes()
        File(dir, "1000-a1.rep").delete()
        File(dir, "1000-a1.tmp").writeBytes(bytes)
        File(dir, "2000-a2.tmp").writeBytes(bytes.copyOf(bytes.size - 3))
        File(dir, "3000-a3.tmp").writeBytes(bytes + byteArrayOf(7))

        val back = store.list()
        assertEquals(listOf("a1"), back.map { it.id })
        assertEquals("<b>a1</b>\nЗаряд 64%", back.single().text)
        assertEquals(listOf("1000-a1.rep"), dir.list()!!.sorted())
    }

    @Test fun `the folder is synced after the rename and after a delete`() {
        val dir = tmp.newFolder()
        val seen = mutableListOf<List<String>>()
        val store = PendingReports(dir, syncDir = { seen += it.list()!!.sorted() })
        store.save(report("a1", 1_000L))
        assertEquals(listOf(listOf("1000-a1.rep")), seen)
        store.delete("1000-a1")
        assertEquals(listOf(emptyList<String>()), seen.drop(1))
    }

    @Test fun `a failing folder sync costs nothing`() {
        val store = PendingReports(tmp.newFolder(), syncDir = { throw IllegalStateException("errno") })
        assertTrue(store.save(report("a1", 1_000L)))
        store.delete("1000-a1")
        assertEquals(0, store.count())
    }
}

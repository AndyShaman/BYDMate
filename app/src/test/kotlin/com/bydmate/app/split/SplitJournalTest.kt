package com.bydmate.app.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitJournalTest {

    private class FakeStore : SplitJournalImpl.Store {
        var value: String = ""
        override fun read(): String = value
        override fun write(value: String) { this.value = value }
    }

    private val store = FakeStore()
    private var clock = 1_000_000L
    private val journal = SplitJournalImpl(store) { clock }

    @Test
    fun `entries are stored in order with a timestamp prefix`() {
        journal.append("start narrow=a wide=b side=RIGHT -> OK")
        clock += 5_000L
        journal.append("end EXIT narrow=a wide=b side=RIGHT")

        val lines = journal.read()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("start narrow=a wide=b side=RIGHT -> OK"))
        assertTrue(lines[1].endsWith("end EXIT narrow=a wide=b side=RIGHT"))
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} """).containsMatchIn(lines[0]))
    }

    @Test
    fun `consecutive identical payloads collapse into one counted line`() {
        // A retrying watchdog decision fires every tick; without collapsing it would
        // flush the whole ring within a minute.
        repeat(3) {
            clock += 1_000L
            journal.append("departed NARROW pkg.a display=2 calibration failed, retry")
        }

        val lines = journal.read()
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith("calibration failed, retry (x3)"))
        // Timestamp is the latest one, not the first.
        assertTrue(lines[0].startsWith(timestampOf(clock)))
    }

    @Test
    fun `a different payload after a collapsed run starts a new line`() {
        journal.append("raise pkg.a attempt 1 did not land (mode=1 display=0)")
        journal.append("raise pkg.a attempt 1 did not land (mode=1 display=0)")
        journal.append("relaunch pkg.a: raise did not land in 2 attempts")

        val lines = journal.read()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("(x2)"))
        assertTrue(lines[1].endsWith("relaunch pkg.a: raise did not land in 2 attempts"))
    }

    @Test
    fun `the ring drops the oldest entries past its capacity`() {
        repeat(SplitJournalImpl.MAX_ENTRIES + 5) { i ->
            clock += 1_000L
            journal.append("event $i")
        }

        val lines = journal.read()
        assertEquals(SplitJournalImpl.MAX_ENTRIES, lines.size)
        assertTrue("oldest entries are dropped", lines.first().endsWith("event 5"))
        assertTrue(lines.last().endsWith("event ${SplitJournalImpl.MAX_ENTRIES + 4}"))
    }

    @Test
    fun `an empty store reads as no entries`() {
        assertEquals(emptyList<String>(), journal.read())
    }

    @Test
    fun `the no-op journal records nothing`() {
        NoSplitJournal.append("start narrow=a wide=b side=RIGHT -> OK")
        assertEquals(emptyList<String>(), NoSplitJournal.read())
    }

    private fun timestampOf(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(ms))
}

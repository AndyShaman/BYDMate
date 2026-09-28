package com.bydmate.app.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The always-on event journal: a bounded ring on disk, written in batches off the caller's
 * thread, readable into the diagnostic dump. The worker runs on a test dispatcher, so every
 * file operation happens only when the test lets the scheduler run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TraceJournalTest {

    @get:Rule val tmp = TemporaryFolder()

    private val file by lazy { File(tmp.root, "trace_journal.txt") }
    private var now = 1_759_050_000_000L // 2025-09-28, local time depends on the machine
    private val logcat = mutableListOf<String>()
    private val journals = mutableListOf<TraceJournal>()

    private fun TestScope.journal(
        target: File? = file,
        maxEvents: Int = TraceJournal.MAX_EVENTS,
        maxBytes: Int = TraceJournal.MAX_BYTES,
        queueCapacity: Int = TraceJournal.QUEUE_CAPACITY,
    ) = TraceJournal(target, StandardTestDispatcher(testScheduler), { now }, { logcat += it }, maxEvents, maxBytes, queueCapacity)
        .also { journals += it }

    private fun TestScope.allLines(j: TraceJournal): List<String> {
        var out: List<String>? = null
        backgroundScope.launch { out = j.lines() }
        runCurrent()
        return requireNotNull(out) { "the snapshot never ran" }
    }

    private fun TestScope.eventLines(j: TraceJournal) = allLines(j).filterNot { it.startsWith("-- ") }

    private fun fileLines() = if (file.exists()) file.readLines().filter { it.isNotEmpty() } else emptyList()

    private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun closeAll() = journals.forEach { it.close() }

    // --- line format and ids ---

    @Test fun `one line per event with area, what, fields, id and cause`() = runTest {
        val j = journal()
        val ptt = j.event(TraceArea.USER, "ptt")
        now += 510
        val heard = j.event(TraceArea.VOICE, "heard", "route" to "agent", "why" to null, "asr_ms" to 412, by = ptt)

        assertEquals(1L, ptt)
        assertEquals(2L, heard)
        assertEquals(
            listOf(
                "${time(now - 510)} user   ptt #1",
                "${time(now)} voice  heard route=agent asr_ms=412 #2 by=#1",
            ),
            eventLines(j),
        )
        assertEquals(listOf("user   ptt #1", "voice  heard route=agent asr_ms=412 #2 by=#1"), logcat)
        closeAll()
    }

    @Test fun `a day change is marked in the dump`() = runTest {
        val j = journal()
        j.event(TraceArea.APP, "start")
        now += 24 * 3_600_000L
        j.event(TraceArea.APP, "service-start")
        val all = allLines(j)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("-- ${day.format(Date(now - 24 * 3_600_000L))} --", all[0])
        assertEquals("-- ${day.format(Date(now))} --", all[2])
        closeAll()
    }

    // --- ring caps ---

    @Test fun `the ring keeps the newest events by count`() = runTest {
        val j = journal(maxEvents = 100)
        repeat(130) { j.event(TraceArea.APP, "tick", "n" to it) }
        val lines = eventLines(j)
        assertEquals(100, lines.size)
        assertTrue(lines.first(), lines.first().endsWith("n=30 #31"))
        assertTrue(lines.last(), lines.last().endsWith("n=129 #130"))
        closeAll()
    }

    @Test fun `the ring and the file stay under the byte cap, oldest dropped`() = runTest {
        val cap = 20_000
        val j = journal(maxBytes = cap)
        val wide = "w".repeat(80)
        repeat(400) { j.event(TraceArea.NET, "call", "a" to wide, "b" to wide, "n" to it) }
        j.flush()
        runCurrent()

        val lines = eventLines(j)
        assertTrue("${lines.size} lines kept", lines.size in 50 until 400)
        assertTrue(lines.last(), lines.last().contains("n=399 #400"))
        assertTrue("file ${file.length()} bytes", file.length() <= cap)
        assertTrue(fileLines().last(), fileLines().last().contains("n=399 #400"))
        closeAll()
    }

    // --- queue backpressure ---

    @Test fun `a full queue drops events instead of growing, and reports how many`() = runTest {
        // Worker not started yet: nothing has run on testScheduler, so all 5 sends land on the
        // channel itself, capacity 3 - events 4 and 5 find it full.
        val j = journal(queueCapacity = 3)
        repeat(5) { j.event(TraceArea.APP, "tick", "n" to it) }
        runCurrent()

        val lines = eventLines(j)
        assertEquals(4, lines.size)
        assertTrue(lines[0], lines[0].endsWith("tick n=0 #1"))
        assertTrue(lines[1], lines[1].endsWith("tick n=1 #2"))
        assertTrue(lines[2], lines[2].endsWith("tick n=2 #3"))
        // Handles are assigned in call order even for a dropped event (#4, #5), so the
        // trace-dropped line itself is #6.
        assertTrue(lines[3], lines[3].endsWith("trace-dropped n=2 #6"))
        closeAll()
    }

    // --- persistence ---

    @Test fun `ids keep increasing after a reload and the old events come back`() = runTest {
        val first = journal()
        first.event(TraceArea.APP, "start", "version" to 493)
        first.event(TraceArea.APP, "service-start")
        first.flush()
        runCurrent()
        first.close()

        val second = journal()
        val next = second.event(TraceArea.APP, "start", "version" to 494)
        val lines = eventLines(second)
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].endsWith("app    start version=493 #1"))
        assertTrue(lines[2], lines[2].endsWith("app    start version=494 #3"))
        // The returned handle links causes inside the process; it renders as the absolute id.
        second.event(TraceArea.APP, "service-start", by = next)
        assertTrue(eventLines(second).last(), eventLines(second).last().endsWith("#4 by=#3"))
        closeAll()
    }

    @Test fun `a corrupt or torn file keeps its readable lines and is rewritten clean`() = runTest {
        file.writeText(
            "\u0000\u0001garbage\n" +
                "$now app    start version=493 #7\n" +
                "not a trace line\n" +
                "$now voice  heard route=ag", // torn by a power cut mid-append
        )
        val j = journal()
        j.event(TraceArea.APP, "start", "version" to 494)
        j.flush()
        runCurrent()

        val lines = eventLines(j)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].endsWith("start version=493 #7"))
        assertTrue(lines[1], lines[1].endsWith("start version=494 #8"))
        // The rewrite leaves only well-formed lines behind, so the next start reads it all.
        assertEquals(2, fileLines().size)
        assertTrue(fileLines().all { it.matches(Regex("""\d+ .+ #\d+.*""")) })
        closeAll()
    }

    @Test fun `an oversized file is dropped whole`() = runTest {
        file.writeText("x".repeat(TraceJournal.MAX_BYTES * 3))
        val j = journal()
        j.event(TraceArea.APP, "start")
        assertEquals(1, eventLines(j).size)
        closeAll()
    }

    // --- batching ---

    @Test fun `an event does no file work on the caller's thread`() = runTest {
        val j = journal()
        j.event(TraceArea.APP, "start")
        // Nothing ran on the worker yet: the caller returned without touching the disk.
        assertFalse(file.exists())
        runCurrent()
        // The worker took the event but the batch is not due yet.
        assertFalse(file.exists())
        closeAll()
    }

    @Test fun `the batch is written after the flush interval`() = runTest {
        val j = journal()
        j.event(TraceArea.APP, "start")
        runCurrent()
        advanceTimeBy(TraceJournal.FLUSH_INTERVAL_MS - 1)
        runCurrent()
        assertFalse(file.exists())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, fileLines().size)
        closeAll()
    }

    @Test fun `the batch is written at the event threshold without waiting`() = runTest {
        val j = journal()
        repeat(TraceJournal.FLUSH_BATCH - 1) { j.event(TraceArea.APP, "tick", "n" to it) }
        runCurrent()
        assertFalse(file.exists())
        j.event(TraceArea.APP, "tick", "n" to "last")
        runCurrent()
        assertEquals(TraceJournal.FLUSH_BATCH, fileLines().size)
        closeAll()
    }

    @Test fun `flush writes the pending events at once`() = runTest {
        val j = journal()
        j.event(TraceArea.APP, "service-stop")
        j.flush()
        runCurrent()
        assertEquals(1, fileLines().size)
        assertTrue(fileLines()[0], fileLines()[0].endsWith(" app    service-stop #1"))
        closeAll()
    }

    @Test fun `appends grow the file, the cap rewrites it with the newest events`() = runTest {
        val cap = 6_000
        val j = journal(maxBytes = cap)
        var largest = 0L
        repeat(150) {
            j.event(TraceArea.APP, "tick", "n" to it, "pad" to "p".repeat(60))
            j.flush()
            runCurrent()
            largest = maxOf(largest, file.length())
        }
        assertTrue("file reached $largest bytes", largest <= cap)
        val stored = fileLines()
        assertTrue(stored.last(), stored.last().contains("n=149 "))
        assertFalse(stored.first(), stored.first().contains("n=0 "))
        closeAll()
    }

    // --- collapse ---

    @Test fun `identical consecutive events collapse into one line with a counter`() = runTest {
        val j = journal()
        val a = j.event(TraceArea.SCREEN, "hint-ignored", "pkg" to "com.byd.avc")
        val b = j.event(TraceArea.SCREEN, "hint-ignored", "pkg" to "com.byd.avc")
        now += 1_000
        val c = j.event(TraceArea.SCREEN, "hint-ignored", "pkg" to "com.byd.avc")
        val d = j.event(TraceArea.WIDGET, "hide", "reason" to "camera", by = c)

        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a + 1, d)
        assertEquals(
            listOf(
                "${time(now)} screen hint-ignored pkg=com.byd.avc #1 (x3)",
                "${time(now)} widget hide reason=camera #2 by=#1",
            ),
            eventLines(j),
        )
        // One logcat line for the first occurrence, not one per repeat.
        assertEquals(2, logcat.size)
        closeAll()
    }

    @Test fun `the same event with another cause is not collapsed`() = runTest {
        val j = journal()
        j.event(TraceArea.WIDGET, "hide", "reason" to "camera", by = 5)
        j.event(TraceArea.WIDGET, "hide", "reason" to "camera", by = 6)
        assertEquals(2, eventLines(j).size)
        closeAll()
    }

    @Test fun `a collapse after a flush survives the reload as one line`() = runTest {
        val first = journal()
        first.event(TraceArea.SCREEN, "hint-ignored", "pkg" to "com.byd.avc")
        first.flush()
        runCurrent()
        first.event(TraceArea.SCREEN, "hint-ignored", "pkg" to "com.byd.avc")
        first.flush()
        runCurrent()
        first.close()

        val lines = eventLines(journal())
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].endsWith("hint-ignored pkg=com.byd.avc #1 (x2)"))
        closeAll()
    }

    // --- misuse ---

    @Test fun `values pass through the sanitizer`() = runTest {
        val j = journal()
        j.event(TraceArea.NET, "overpass", "url" to "https://overpass-api.de/api/interpreter?data=55.75,37.61", "q" to "закрой окна")
        assertTrue(eventLines(j)[0], eventLines(j)[0].contains("net    overpass url=overpass-api.de/... q=<text> #1"))
        closeAll()
    }

    @Test fun `a memory-only journal never touches the disk`() = runTest {
        val j = journal(target = null)
        repeat(TraceJournal.FLUSH_BATCH + 1) { j.event(TraceArea.APP, "tick", "n" to it) }
        j.flush()
        runCurrent()
        advanceTimeBy(TraceJournal.FLUSH_INTERVAL_MS * 2)
        runCurrent()
        assertEquals(TraceJournal.FLUSH_BATCH + 1, eventLines(j).size)
        assertEquals(0, tmp.root.listFiles()!!.size)
        closeAll()
    }
}

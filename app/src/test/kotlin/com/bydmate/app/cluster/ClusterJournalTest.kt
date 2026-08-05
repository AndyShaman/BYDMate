package com.bydmate.app.cluster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClusterJournalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("cluster_journal_test", Context.MODE_PRIVATE)
    private var clock = 1_700_000_000_000L

    @Before fun clear() {
        prefs.edit().clear().commit()
        clock = 1_700_000_000_000L
    }

    private fun journal() = ClusterJournal(prefs) { clock }

    @Test fun `entries are timestamped and kept in order`() {
        val j = journal()
        j.append("setMode OFF -> FULLSCREEN (reason=star_key)")
        clock += 1000
        j.append("direct OK: pkg display=2")

        val lines = j.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("setMode OFF -> FULLSCREEN (reason=star_key)"))
        assertTrue(lines[1].endsWith("direct OK: pkg display=2"))
        assertTrue(lines[0].matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} .*""")))
    }

    @Test fun `repeated payload collapses into a counter with the latest timestamp`() {
        val j = journal()
        j.append("abort: helper daemon not running")
        clock += 60_000
        j.append("abort: helper daemon not running")
        clock += 60_000
        j.append("abort: helper daemon not running")

        val lines = j.lines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().endsWith("abort: helper daemon not running (x3)"))
        // Latest timestamp, not the first one.
        assertTrue(lines.single().startsWith(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(clock))))
    }

    @Test fun `a different payload after a collapsed run starts a new line`() {
        val j = journal()
        j.append("vd: create failed 1280x480@320")
        j.append("vd: create failed 1280x480@320")
        j.append("projection failed (projection); falling back to OFF")

        val lines = j.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("vd: create failed 1280x480@320 (x2)"))
        assertTrue(lines[1].endsWith("projection failed (projection); falling back to OFF"))
    }

    @Test fun `ring drops the oldest entries past the cap`() {
        val j = journal()
        repeat(ClusterJournal.MAX_ENTRIES + 5) { i ->
            clock += 1000
            j.append("event $i")
        }

        val lines = j.lines()
        assertEquals(ClusterJournal.MAX_ENTRIES, lines.size)
        assertTrue(lines.first().endsWith("event 5"))
        assertTrue(lines.last().endsWith("event ${ClusterJournal.MAX_ENTRIES + 4}"))
    }

    @Test fun `a fresh reader sees what a prior instance wrote`() {
        journal().append("setMode OFF -> FULLSCREEN (reason=voice)")
        assertEquals(1, ClusterJournal(prefs).lines().size)
    }

    /**
     * The projection and the blind-spot camera write into the same ring from different threads.
     * Every append rewrites it whole, so the write is only safe while both go through one
     * instance — @Synchronized guards the instance, not the prefs key.
     */
    @Test fun `concurrent producers on one instance lose no entries`() {
        val j = journal()
        val producers = 6
        val perProducer = ClusterJournal.MAX_ENTRIES / producers  // total fills the ring exactly
        val start = CountDownLatch(1)
        val done = CountDownLatch(producers)
        repeat(producers) { p ->
            Thread {
                start.await()
                repeat(perProducer) { i -> j.append("producer $p entry $i") }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("producers must finish", done.await(10, TimeUnit.SECONDS))

        val lines = j.lines()
        assertEquals(producers * perProducer, lines.size)
        repeat(producers) { p ->
            repeat(perProducer) { i ->
                assertTrue("entry $p/$i survived", lines.any { it.endsWith("producer $p entry $i") })
            }
        }
    }

    /** What makes that one instance reachable from both owners (#135). */
    @Test fun `shared hands every consumer the same instance`() {
        assertSame(ClusterJournal.shared(context), ClusterJournal.shared(context))
    }
}

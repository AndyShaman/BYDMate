package com.bydmate.app.helper.offreport

import com.bydmate.app.data.telegram.TelegramReportBuilder
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.helper.push.FidPushSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The daemon's power-off report end to end, without the firmware: a fake listener registration
 * hands over the sink, a fake sender plays Telegram, the send runs on the calling thread and the
 * worker's tasks run when the test says so ([work]).
 */
class OffReportTest {

    private val level = 315621418
    private val powerState = 315621408

    private var sink: FidPushSink? = null
    private var wall = 1_790_000_000_000L
    private var mono = 50_000L
    private val sentTexts = mutableListOf<String>()
    private var answers: (Int) -> AttemptResult = { AttemptResult("200", AttemptResult.Verdict.SENT) }
    private val reads = mutableMapOf<Int, Int?>()
    private val registered = mutableListOf<Int>()
    private var registerAnswer: (fid: Int) -> String = { FID_PUSH_OK }
    private val queued = ArrayDeque<Runnable>()
    private val delayed = mutableListOf<Pair<Long, Runnable>>()

    private val realRegistrar = OffReport.registrar
    private val realSender = OffReport.sender
    private val realWall = OffReport.wallClock
    private val realMono = OffReport.monoClock
    private val realSleep = OffReport.sleep
    private val realThread = OffReport.startThread
    private val realReader = OffReport.reader
    private val realSchedule = OffReport.schedule

    @Before fun setUp() {
        OffReport.resetForTest()
        OffReport.registrar = { _, fid, s -> sink = s; registered += fid; registerAnswer(fid) }
        OffReport.schedule = { delayMs, task -> if (delayMs == 0L) queued.addLast(task) else delayed += delayMs to task }
        OffReport.sender = { _, text, _, _ ->
            sentTexts += text
            mono += 100
            answers(sentTexts.size)
        }
        OffReport.wallClock = { wall }
        OffReport.monoClock = { mono }
        OffReport.sleep = { mono += it }
        OffReport.startThread = { it.run() }
        OffReport.reader = { _, fid -> reads[fid] }
    }

    @After fun tearDown() {
        OffReport.resetForTest()
        OffReport.registrar = realRegistrar
        OffReport.sender = realSender
        OffReport.wallClock = realWall
        OffReport.monoClock = realMono
        OffReport.sleep = realSleep
        OffReport.startThread = realThread
        OffReport.reader = realReader
        OffReport.schedule = realSchedule
    }

    /** Runs the worker's queued tasks, as the worker thread would. */
    private fun work() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private fun arm(id: String, text: String = "<b>off at ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>") {
        OffReport.arm(ArmRequest(id, "tok", 42L, text))
        work()
    }

    private fun push(fid: Int, value: Int) = sink!!.onEvent(fid, value, 0.0, mono)

    @Test fun `the armed report goes out at the power-off with the time filled in`() {
        reads[level] = 2
        arm("a1")
        push(level, 0)
        assertEquals(listOf("<b>off at ${TelegramReportBuilder.formatTime(wall)}</b>"), sentTexts)
        val q = OffReport.status("a1").queried
        assertEquals(OffReportState.SENT, q.state)
        assertEquals(wall, q.powerOffMs)
        assertEquals(1, q.attempts)
        assertEquals("200", q.rc)
    }

    @Test fun `the listener is registered once and the arm seeds it from a direct read`() {
        reads[level] = 2
        arm("a1")
        arm("a2")
        assertEquals(listOf(level, powerState), registered)
        assertEquals(2, OffReport.status("a2").listening)
        assertEquals(OffReportState.ARMED, OffReport.status("a2").queried.state)
        assertEquals(OffReportState.UNKNOWN, OffReport.status("a1").queried.state)
    }

    @Test fun `a daemon armed on a car that reads off sends nothing on a 0`() {
        reads[level] = 0
        reads[powerState] = 0
        arm("a1")
        push(level, 0)
        push(powerState, 0)
        assertTrue(sentTexts.isEmpty())
        assertEquals(OffReportState.ARMED, OffReport.status("a1").queried.state)
    }

    @Test fun `one power-off sends one report even when both fids drop`() {
        reads[level] = 2
        reads[powerState] = 1
        arm("a1")
        push(level, 0)
        push(powerState, 0)
        assertEquals(1, sentTexts.size)
    }

    @Test fun `the report is used up by its power-off, a quick off-on-off sends nothing more`() {
        reads[level] = 2
        arm("a1")
        push(level, 0)
        push(level, 2)
        push(level, 0)
        assertEquals(1, sentTexts.size)
        assertNull(OffReport.status("a1").armedId.ifEmpty { null })
    }

    @Test fun `after power returns a fresh arm fires again`() {
        reads[level] = 2
        arm("a1")
        push(level, 0)
        push(level, 2)
        arm("a2")
        push(level, 0)
        assertEquals(2, sentTexts.size)
        assertEquals(OffReportState.SENT, OffReport.status("a2").queried.state)
        assertEquals("a2", OffReport.status("").last?.id)
    }

    @Test fun `every attempt failing until the deadline leaves a failed outcome with the power-off time`() {
        answers = { AttemptResult("io:SocketTimeoutException", AttemptResult.Verdict.RETRY) }
        reads[level] = 2
        arm("a1")
        push(level, 0)
        val q = OffReport.status("a1").queried
        assertEquals(OffReportState.FAILED, q.state)
        assertEquals(wall, q.powerOffMs)
        assertEquals(0L, q.sentAtMs)
        assertEquals("io:SocketTimeoutException", q.rc)
        assertTrue(q.attempts > 1)
    }

    @Test fun `a disarmed daemon sends nothing`() {
        reads[level] = 2
        arm("a1")
        OffReport.disarm()
        push(level, 0)
        assertTrue(sentTexts.isEmpty())
        assertEquals(OffReportState.UNKNOWN, OffReport.status("a1").queried.state)
    }

    @Test fun `a crashing send is recorded as failed instead of killing the daemon`() {
        OffReport.sender = { _, _, _, _ -> throw IllegalStateException("boom") }
        reads[level] = 2
        arm("a1")
        push(level, 0)
        val q = OffReport.status("a1").queried
        assertEquals(OffReportState.FAILED, q.state)
        assertEquals("crash", q.rc)
    }

    @Test fun `a failing registration leaves the report armed with no listener`() {
        OffReport.registrar = { _, _, _ -> throw NoClassDefFoundError("AbsBYDAutoBodyworkListener") }
        arm("a1")
        val status = OffReport.status("a1")
        assertEquals(0, status.listening)
        assertEquals(OffReportState.ARMED, status.queried.state)
        assertTrue(status.fids.all { it.outcome.startsWith("NoClassDefFoundError") })
    }

    @Test fun `the arm only stores, registration and reads wait for the worker`() {
        reads[level] = 2
        OffReport.arm(ArmRequest("a1", "tok", 42L, "t"))
        assertTrue(registered.isEmpty())
        val before = OffReport.status("a1")
        assertEquals(OffReportState.ARMED, before.queried.state)
        assertEquals(listOf(OffReport.FID_PENDING, OffReport.FID_PENDING), before.fids.map { it.outcome })
        assertEquals(0, before.listening)
        work()
        assertEquals(listOf(FID_PUSH_OK, FID_PUSH_OK), OffReport.status("a1").fids.map { it.outcome })
    }

    @Test fun `a re-arm while the car reads 0 does not swallow the pending power-off push`() {
        reads[level] = 2
        arm("a1")
        reads[level] = 0 // the car went off; its push is still queued behind this arm
        arm("a2")
        push(level, 0)
        assertEquals(1, sentTexts.size)
        assertEquals(OffReportState.SENT, OffReport.status("a2").queried.state)
    }

    @Test fun `a missing fid is retried every 30 s until it registers, one retry at a time`() {
        var fail = true
        registerAnswer = { fid -> if (fid == powerState && fail) "-10011" else FID_PUSH_OK }
        arm("a1")
        arm("a2")
        assertEquals(1, delayed.size)
        assertEquals(OffReport.REGISTER_RETRY_MS, delayed.single().first)
        assertEquals(listOf(level, powerState), registered)
        assertEquals(1, OffReport.status("a2").listening)

        delayed.removeAt(0).second.run()
        assertEquals(listOf(level, powerState, powerState), registered)
        assertEquals(1, delayed.size)

        fail = false
        delayed.removeAt(0).second.run()
        assertTrue(delayed.isEmpty())
        assertEquals(2, OffReport.status("a2").listening)
        assertEquals(listOf(level, powerState, powerState, powerState), registered)
    }

    @Test fun `the send runs off the vendor thread, and the arm request never prints its secrets`() {
        var started: Runnable? = null
        OffReport.startThread = { started = it }
        reads[level] = 2
        arm("a1")
        push(level, 0)
        assertTrue(sentTexts.isEmpty())
        assertEquals(OffReportState.SENDING, OffReport.status("a1").queried.state)
        started!!.run()
        assertEquals(OffReportState.SENT, OffReport.status("a1").queried.state)
        assertFalse(ArmRequest("a1", "tok", 42L, "secret").toString().contains("tok"))
        assertFalse(ArmRequest("a1", "tok", 42L, "secret").toString().contains("secret"))
    }
}

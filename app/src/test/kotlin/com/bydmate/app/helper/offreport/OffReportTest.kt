package com.bydmate.app.helper.offreport

import com.bydmate.app.data.telegram.TelegramReportBuilder
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
 * hands over the sink, a fake sender plays Telegram, the send runs on the calling thread.
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

    private val realRegistrar = OffReport.registrar
    private val realSender = OffReport.sender
    private val realWall = OffReport.wallClock
    private val realMono = OffReport.monoClock
    private val realSleep = OffReport.sleep
    private val realThread = OffReport.startThread
    private val realReader = OffReport.reader

    @Before fun setUp() {
        OffReport.resetForTest()
        OffReport.registrar = { s -> sink = s; 2 }
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
    }

    private fun arm(id: String, text: String = "<b>off at ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>") =
        OffReport.arm(ArmRequest(id, "tok", 42L, text))

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
        var registrations = 0
        OffReport.registrar = { s -> sink = s; registrations++; 2 }
        reads[level] = 2
        arm("a1")
        arm("a2")
        assertEquals(1, registrations)
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
        OffReport.registrar = { throw NoClassDefFoundError("AbsBYDAutoBodyworkListener") }
        arm("a1")
        val status = OffReport.status("a1")
        assertEquals(0, status.listening)
        assertEquals(OffReportState.ARMED, status.queried.state)
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

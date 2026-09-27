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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The daemon's power-off report end to end, without the firmware: a fake listener registration
 * hands over the sink, a fake sender plays Telegram, pending reports go to a temp folder, the sender
 * jobs run on the calling thread (the real `offreport-send` thread where a test is about
 * interleaving) and the worker's tasks run when the test says so ([work]).
 */
class OffReportTest {

    @get:Rule val tmp = TemporaryFolder()

    private val level = 315621418
    private val powerState = 315621408

    private class Sent(val token: String, val chatId: Long, val text: String)

    private var sink: FidPushSink? = null
    private var wall = 1_790_000_000_000L
    private var mono = 50_000L
    private val sent = mutableListOf<Sent>()
    private val sentTexts get() = sent.map { it.text }
    private var answers: (Int) -> AttemptResult = { ok }
    private var onSend: () -> Unit = {}
    private val reads = mutableMapOf<Int, Int?>()
    private val registered = mutableListOf<Int>()
    private var registerAnswer: (fid: Int) -> String = { FID_PUSH_OK }
    private val queued = ArrayDeque<Runnable>()
    private val delayed = mutableListOf<Pair<Long, Runnable>>()
    private lateinit var pending: PendingReports
    private lateinit var dir: File

    private val ok = AttemptResult("200", AttemptResult.Verdict.SENT)
    private val noNet = AttemptResult("io:UnknownHostException", AttemptResult.Verdict.RETRY)
    private val refused = AttemptResult("403", AttemptResult.Verdict.STOP)

    private val realRegistrar = OffReport.registrar
    private val realSender = OffReport.sender
    private val realWall = OffReport.wallClock
    private val realMono = OffReport.monoClock
    private val realSleep = OffReport.sleep
    private val realSend = OffReport.send
    private val realReader = OffReport.reader
    private val realSchedule = OffReport.schedule
    private val realPending = OffReport.pending

    @Before fun setUp() {
        OffReport.resetForTest()
        dir = tmp.newFolder("offreport")
        pending = PendingReports(dir, syncDir = {})
        OffReport.pending = pending
        OffReport.registrar = { _, fid, s -> sink = s; registered += fid; registerAnswer(fid) }
        OffReport.schedule = { delayMs, task -> if (delayMs == 0L) queued.addLast(task) else delayed += delayMs to task }
        OffReport.sender = { token, chatId, text, _, _ ->
            onSend()
            sent += Sent(token, chatId, text)
            mono += 100
            answers(sent.size)
        }
        OffReport.wallClock = { wall }
        OffReport.monoClock = { mono }
        OffReport.sleep = { mono += it }
        OffReport.send = { it.run() }
        OffReport.reader = { _, fid -> reads[fid] }
    }

    @After fun tearDown() {
        OffReport.resetForTest()
        OffReport.registrar = realRegistrar
        OffReport.sender = realSender
        OffReport.wallClock = realWall
        OffReport.monoClock = realMono
        OffReport.sleep = realSleep
        OffReport.send = realSend
        OffReport.reader = realReader
        OffReport.schedule = realSchedule
        OffReport.pending = realPending
    }

    /** Runs the worker's queued tasks, as the worker thread would. */
    private fun work() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private fun arm(id: String, text: String = "<b>off at ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>", chatId: Long = 42L) {
        OffReport.arm(ArmRequest(id, "tok-$id", chatId, text, "<i>(at ${TelegramReportBuilder.TIME_PLACEHOLDER})</i>"))
        work()
    }

    private fun push(fid: Int, value: Int) = sink!!.onEvent(fid, value, 0.0, mono)

    /** A power cycle: on, armed with [id], off. */
    private fun cycle(id: String) {
        reads[level] = 2
        arm(id, "<b>$id off at ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>")
        push(level, 2)
        push(level, 0)
    }

    // --- at the power-off ---

    @Test fun `the armed report goes out at the power-off with the time filled in`() {
        reads[level] = 2
        arm("a1")
        push(level, 0)
        assertEquals(listOf("<b>off at ${TelegramReportBuilder.formatTime(wall)}</b>"), sentTexts)
        assertEquals("tok-a1", sent.single().token)
        val q = OffReport.status("a1").queried
        assertEquals(OffReportState.SENT, q.state)
        assertEquals(wall, q.powerOffMs)
        assertEquals(1, q.attempts)
        assertEquals("200", q.rc)
        assertEquals(0, pending.count())
    }

    @Test fun `the report is on disk before the first attempt, and gone once it was sent`() {
        val onDiskAtSend = mutableListOf<Int>()
        onSend = { onDiskAtSend += pending.count() }
        reads[level] = 2
        arm("a1")
        push(level, 0)
        assertEquals(listOf(1), onDiskAtSend)
        assertEquals(0, OffReport.status("").pending)
    }

    @Test fun `every attempt failing until the deadline keeps the report on disk`() {
        answers = { noNet }
        reads[level] = 2
        arm("a1")
        push(level, 0)
        val status = OffReport.status("a1")
        assertEquals(OffReportState.FAILED, status.queried.state)
        assertEquals(wall, status.queried.powerOffMs)
        assertEquals("io:UnknownHostException", status.queried.rc)
        assertTrue(status.queried.attempts > 1)
        assertEquals(1, status.pending)
        val kept = pending.list().single()
        assertEquals("a1", kept.id)
        assertEquals(42L, kept.chatId)
        assertEquals(wall, kept.powerOffMs)
        assertEquals("<b>off at ${TelegramReportBuilder.formatTime(wall)}</b>", kept.text)
    }

    @Test fun `a refusal for good at the power-off drops the report`() {
        answers = { refused }
        reads[level] = 2
        arm("a1")
        push(level, 0)
        assertEquals(1, sent.size)
        assertEquals(OffReportState.FAILED, OffReport.status("a1").queried.state)
        assertEquals(0, pending.count())
    }

    @Test fun `a daemon armed on a car that reads off sends nothing on a 0`() {
        reads[level] = 0
        reads[powerState] = 0
        arm("a1")
        push(level, 0)
        push(powerState, 0)
        assertTrue(sent.isEmpty())
        assertEquals(OffReportState.ARMED, OffReport.status("a1").queried.state)
    }

    @Test fun `one power-off sends one report even when both fids drop`() {
        reads[level] = 2
        reads[powerState] = 1
        arm("a1")
        push(level, 0)
        push(powerState, 0)
        assertEquals(1, sent.size)
    }

    @Test fun `the report is used up by its power-off, a quick off-on-off sends nothing more`() {
        reads[level] = 2
        arm("a1")
        push(level, 0)
        push(level, 2)
        push(level, 0)
        assertEquals(1, sent.size)
        assertNull(OffReport.status("a1").armedId.ifEmpty { null })
    }

    @Test fun `after power returns a fresh arm fires again`() {
        reads[level] = 2
        arm("a1")
        push(level, 0)
        push(level, 2)
        arm("a2")
        push(level, 0)
        assertEquals(2, sent.size)
        assertEquals(OffReportState.SENT, OffReport.status("a2").queried.state)
        assertEquals("a2", OffReport.status("").last?.id)
    }

    @Test fun `a disarmed daemon sends nothing and drops what was pending`() {
        answers = { noNet }
        cycle("a1")
        assertEquals(1, pending.count())
        val before = sent.size
        arm("a2")
        OffReport.disarm()
        push(level, 0)
        assertEquals(0, pending.count())
        assertEquals(OffReportState.UNKNOWN, OffReport.status("a2").queried.state)
        assertEquals("only the pass a2's arm started, nothing after the disarm", 1, sent.size - before)
    }

    @Test fun `a crashing send is recorded as failed instead of killing the daemon`() {
        OffReport.sender = { _, _, _, _, _ -> throw IllegalStateException("boom") }
        reads[level] = 2
        arm("a1")
        push(level, 0)
        val q = OffReport.status("a1").queried
        assertEquals(OffReportState.FAILED, q.state)
        assertEquals("crash", q.rc)
    }

    @Test fun `the send leaves the vendor thread for the sender, and the arm request never prints its secrets`() {
        var started: Runnable? = null
        OffReport.send = { started = it }
        reads[level] = 2
        OffReport.arm(ArmRequest("a1", "tok", 42L, "t", ""))
        work()
        started = null // the pending pass of the arm
        push(level, 0)
        assertTrue(sent.isEmpty())
        assertEquals(OffReportState.SENDING, OffReport.status("a1").queried.state)
        started!!.run()
        assertEquals(OffReportState.SENT, OffReport.status("a1").queried.state)
        assertFalse(ArmRequest("a1", "tok", 42L, "secret", "").toString().contains("tok"))
        assertFalse(ArmRequest("a1", "tok", 42L, "secret", "").toString().contains("secret"))
    }

    // --- pending delivery ---

    @Test fun `a pending report waits for an armed token, then goes out once with the late mark`() {
        answers = { noNet }
        cycle("a1")
        val afterOff = sent.size
        val offAt = wall
        push(level, 2) // car on again, nothing armed yet
        assertEquals(afterOff, sent.size)

        answers = { ok }
        arm("b1")
        val delivered = sent.drop(afterOff).single()
        assertEquals("tok-b1", delivered.token)
        assertEquals(
            "<b>a1 off at ${TelegramReportBuilder.formatTime(offAt)}</b>\n<i>(at ${TelegramReportBuilder.formatTime(offAt)})</i>",
            delivered.text,
        )
        assertEquals(0, pending.count())
        val outcome = OffReport.status("a1").queried
        assertEquals(OffReportState.SENT, outcome.state)
        assertEquals(offAt, outcome.powerOffMs)

        arm("b2")
        assertEquals(afterOff + 1, sent.size)
    }

    @Test fun `pending reports go oldest first`() {
        answers = { noNet }
        cycle("a1")
        wall += 60_000L
        cycle("a2")
        val before = sent.size
        answers = { ok }
        mono += OffReport.PENDING_BACKOFF_MAX_MS // past the backoff of a2's arm, which met no network
        arm("b1")
        assertEquals(listOf("<b>a1", "<b>a2"), sent.drop(before).map { it.text.substringBefore(" off") })
        assertEquals(0, pending.count())
    }

    @Test fun `a pending report refused for good is dropped and the next one still goes`() {
        answers = { noNet }
        cycle("a1")
        wall += 60_000L
        cycle("a2")
        val before = sent.size
        answers = { n -> if (n == before + 1) refused else ok }
        mono += OffReport.PENDING_BACKOFF_MAX_MS
        arm("b1")
        assertEquals(2, sent.size - before)
        assertEquals(0, pending.count())
        assertEquals(OffReportState.FAILED, OffReport.status("a1").queried.state)
        assertEquals("403", OffReport.status("a1").queried.rc)
        assertEquals(OffReportState.SENT, OffReport.status("a2").queried.state)
    }

    @Test fun `no network on a pending pass keeps the report and backs off`() {
        answers = { noNet }
        cycle("a1")
        val before = sent.size
        arm("b1")
        assertEquals(before + 1, sent.size)
        assertEquals(1, pending.count())

        arm("b2") // within the backoff: no pass
        assertEquals(before + 1, sent.size)

        mono += OffReport.PENDING_BACKOFF_FIRST_MS
        answers = { ok }
        arm("b3")
        assertEquals(before + 2, sent.size)
        assertEquals(0, pending.count())
    }

    @Test fun `a pending report built for another chat is dropped, never sent there`() {
        answers = { noNet }
        cycle("a1")
        val before = sent.size
        answers = { ok }
        arm("b1", chatId = 777L)
        assertEquals(before, sent.size)
        assertEquals(0, pending.count())
    }

    // --- listener and priming ---

    @Test fun `the listener is registered once and the arm seeds it from a direct read`() {
        reads[level] = 2
        arm("a1")
        arm("a2")
        assertEquals(listOf(level, powerState), registered)
        assertEquals(2, OffReport.status("a2").listening)
        assertEquals(OffReportState.ARMED, OffReport.status("a2").queried.state)
        assertEquals(OffReportState.UNKNOWN, OffReport.status("a1").queried.state)
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
        OffReport.arm(ArmRequest("a1", "tok", 42L, "t", ""))
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
        assertEquals(1, sent.size)
        assertEquals(OffReportState.SENT, OffReport.status("a2").queried.state)
    }

    @Test fun `reads taken before a handled power-off cannot re-prime it for a second report`() {
        reads[level] = 2
        arm("a1")
        // The worker reads for a2's arm: level=2, power=1. While it reads, the power-off's first 0
        // push sends a2 and the app's next arm sets b1; the stale non-zero reads land after that.
        var raced = false
        OffReport.reader = { _, fid ->
            if (!raced) {
                raced = true
                push(level, 0)
                OffReport.arm(ArmRequest("b1", "tok-b1", 42L, "b1", ""))
            }
            if (fid == level) 2 else 1
        }
        OffReport.arm(ArmRequest("a2", "tok-a2", 42L, "a2", ""))
        queued.removeFirst().run() // a2's prime, racing
        assertEquals(listOf("a2"), sentTexts)
        // The second fid's 0 of the same power-off, and a repeated 0: b1 must not go out.
        push(powerState, 0)
        push(level, 0)
        assertEquals(listOf("a2"), sentTexts)
        assertEquals(OffReportState.ARMED, OffReport.status("b1").queried.state)
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

    // --- one sender ---

    @Test fun `every send runs on the one sender thread, never two at once`() {
        OffReport.send = realSend
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val threads = Collections.synchronizedSet(HashSet<String>())
        val burstStarted = CountDownLatch(1)
        OffReport.sender = { _, _, _, _, _ ->
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            threads += Thread.currentThread().name
            burstStarted.countDown()
            Thread.sleep(50)
            inFlight.decrementAndGet()
            if (calls.incrementAndGet() == 1) noNet else ok
        }
        OffReport.sleep = { mono += OffReportRetry.DEADLINE_MS } // one failed burst attempt, then the deadline
        reads[level] = 2
        arm("a1")
        push(level, 0) // the burst: on the sender thread, fails, a1 stays pending
        assertTrue(burstStarted.await(5, TimeUnit.SECONDS))
        arm("b1") // while the burst is in flight: the pending pass for a1 queues behind it
        val deadline = System.currentTimeMillis() + 5_000
        while ((calls.get() < 2 || pending.count() > 0) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, calls.get())
        assertEquals(0, pending.count())
        assertEquals(1, maxInFlight.get())
        assertEquals(setOf("offreport-send"), threads.toSet())
        assertEquals(OffReportState.SENT, OffReport.status("a1").queried.state)
    }

    // --- on the real sender thread: disarm, chat change and backoff against a send in flight ---

    /** Waits until everything queued on the sender so far has run. */
    private fun drainSender() {
        val done = CountDownLatch(1)
        OffReport.send(Runnable { done.countDown() })
        assertTrue("sender idle", done.await(5, TimeUnit.SECONDS))
    }

    /** A sender whose first call tells [started], then waits for [release] (bounded). */
    private fun blockingSender(started: CountDownLatch, release: CountDownLatch, answer: AttemptResult) {
        val calls = AtomicInteger(0)
        OffReport.sender = { token, chatId, text, _, _ ->
            synchronized(sent) { sent += Sent(token, chatId, text) }
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            answer
        }
    }

    @Test fun `a disarm between the power-off and its send cancels the send and the file`() {
        OffReport.send = realSend
        reads[level] = 2
        arm("a1")
        drainSender() // a1's pending pass, nothing on disk
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        OffReport.send(Runnable { busy.countDown(); release.await(5, TimeUnit.SECONDS) })
        assertTrue(busy.await(5, TimeUnit.SECONDS))
        push(level, 0) // taken and saved here; its send waits behind the busy sender
        assertEquals(1, pending.count())
        assertTrue(OffReport.disarm())
        assertEquals(0, pending.count())
        release.countDown()
        drainSender()
        assertTrue(sent.isEmpty())
        assertEquals(0, pending.count())
        val q = OffReport.status("a1").queried
        assertEquals(OffReportState.FAILED, q.state)
        assertEquals("disarm", q.rc)
    }

    @Test fun `an arm to another chat during a pass drops the old chat's remaining reports`() {
        answers = { noNet }
        cycle("a1")
        wall += 60_000L
        cycle("a2")
        assertEquals(2, pending.count())
        val before = sent.size
        mono += OffReport.PENDING_BACKOFF_MAX_MS
        OffReport.send = realSend
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        blockingSender(started, release, ok)
        arm("b1") // same chat 42: the pass starts with a1 and hangs in its send
        assertTrue(started.await(5, TimeUnit.SECONDS))
        arm("c1", chatId = 777L)
        release.countDown()
        drainSender()
        val delivered = synchronized(sent) { sent.drop(before) }
        assertEquals("a1 was in flight before the new chat, a2 never goes", listOf("<b>a1"), delivered.map { it.text.substringBefore(" off") })
        assertEquals(0, pending.count())
        assertEquals(OffReportState.SENT, OffReport.status("a1").queried.state)
    }

    @Test fun `a power-off during a slow pending send is on disk before that send ends`() {
        answers = { noNet }
        cycle("a1")
        mono += OffReport.PENDING_BACKOFF_MAX_MS
        OffReport.send = realSend
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        blockingSender(started, release, noNet)
        reads[level] = 2
        arm("b1") // the pass for a1 hangs in its send
        assertTrue(started.await(5, TimeUnit.SECONDS))
        push(level, 0)
        assertEquals("the pending send is still in flight", 1L, release.count)
        assertEquals(listOf("a1", "b1"), pending.list().map { it.id })
        release.countDown()
        drainSender()
        assertEquals(listOf("a1", "b1"), pending.list().map { it.id })
    }

    @Test fun `an arm during a pass starts no second pass inside the backoff`() {
        answers = { noNet }
        cycle("a1")
        mono += OffReport.PENDING_BACKOFF_MAX_MS
        val before = sent.size
        OffReport.send = realSend
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        blockingSender(started, release, noNet)
        arm("b1")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        arm("b2") // the pass is still running: nothing more is queued
        release.countDown()
        drainSender()
        arm("b3") // inside the backoff the failed pass set
        drainSender()
        assertEquals(1, synchronized(sent) { sent.size } - before)
        assertEquals(1, pending.count())
    }

    @Test fun `a disarm that cannot delete a pending file is not confirmed`() {
        answers = { noNet }
        cycle("a1")
        val stuck = File(dir, "5-stuck.rep").apply { mkdirs() } // a non-empty directory: delete() fails
        File(stuck, "x").writeText("x")
        assertFalse(OffReport.disarm())
        assertEquals(0, pending.count())
        stuck.deleteRecursively()
        assertTrue(OffReport.disarm())
    }
}

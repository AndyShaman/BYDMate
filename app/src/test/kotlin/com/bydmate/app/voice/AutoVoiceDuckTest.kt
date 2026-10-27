package com.bydmate.app.voice

import com.bydmate.app.data.vehicle.AutoVoiceReply
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.split.fakeSplitSharedPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scope for the duck's daemon calls on the test scheduler. Not backgroundScope: advanceUntilIdle()
 * does not wait for background work, and the queue worker never ends, so it must not be a child of
 * the test either.
 */
internal fun TestScope.duckScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

/** App-side firmware voice duck state machine (#353), on virtual time: the daemon calls run on duckScope(). */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoVoiceDuckTest {

    private val applied = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_APPLIED, "ok")
    private val unsupported = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, "no method")
    private val failed = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_FAILED, "boom")

    private val backing = mutableMapOf<String, Any?>()
    private val prefs = fakeSplitSharedPrefs(backing)
    private val calls = mutableListOf<Boolean>()
    private val mediaDucks = mutableListOf<Int>()
    private val mediaRestores = mutableListOf<Int?>()

    private fun marker() = backing[AutoVoiceDuck.KEY_MARKER] == true

    private val duckMedia: () -> Int? = { mediaDucks += 20; 20 }
    private val restoreMedia: (Int?) -> Unit = { mediaRestores += it }

    /** [onOn] answers the `true` call, [onOff] every `false` call (the Nth one gets the Nth reply when it is a list). */
    private fun TestScope.duck(
        onOn: suspend () -> AutoVoiceReply? = { applied },
        onOff: suspend () -> AutoVoiceReply? = { applied },
        retries: List<Long> = emptyList(),
    ) = AutoVoiceDuck(
        setActive = { on -> calls += on; if (on) onOn() else onOff() },
        prefs = prefs,
        scope = duckScope(),
        offRetryDelaysMs = retries,
    )

    private fun TestScope.duckWithOffAnswers(vararg answers: AutoVoiceReply?, retries: List<Long>): AutoVoiceDuck {
        var n = 0
        return duck(onOff = { answers[minOf(n++, answers.size - 1)] }, retries = retries)
    }

    @Test fun `applied - activates, marks, and deactivate sends false and clears the marker`() = runTest {
        val d = duck()

        assertTrue(d.mayApply())
        val lease = d.activate(duckMedia, restoreMedia)
        assertTrue(d.isActive())
        assertTrue(marker())
        assertEquals(listOf(true), calls)
        assertTrue(mediaDucks.isEmpty())

        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf(true, false), calls)
        assertFalse(d.isActive())
        assertFalse(marker())
    }

    @Test fun `unsupported - media duck, verdict cached, second activate does not call, releases send nothing`() = runTest {
        val d = duck(onOn = { unsupported })

        val l1 = d.activate(duckMedia, restoreMedia)
        assertFalse(d.mayApply())
        assertFalse(marker())
        val l2 = d.activate(duckMedia, restoreMedia)
        d.deactivate(l1)
        d.deactivate(l2)
        advanceUntilIdle()

        assertEquals(listOf(true), calls)
        assertEquals(listOf(20, 20), mediaDucks)
        assertEquals(listOf<Int?>(20, 20), mediaRestores)
    }

    @Test fun `failed - media duck, deactivate still sends false and the marker stays`() = runTest {
        val d = duck(onOn = { failed }, onOff = { failed })

        val lease = d.activate(duckMedia, restoreMedia)
        assertFalse(d.isActive())
        assertTrue(d.mayApply())
        assertEquals(listOf(20), mediaDucks)
        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf(true, false), calls)
        assertTrue(marker())
        assertEquals(listOf<Int?>(20), mediaRestores)
    }

    @Test fun `no answer - media duck, deactivate still sends false and clears the marker once applied`() = runTest {
        val d = duck(onOn = { null })

        val lease = d.activate(duckMedia, restoreMedia)
        assertFalse(d.isActive())
        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf(true, false), calls)
        assertFalse(marker())
    }

    @Test fun `slow daemon - activate returns at 250 ms with the media duck, off still sent`() = runTest {
        val d = duck(onOn = { withContext(NonCancellable) { delay(10_000) }; applied })

        val lease = d.activate(duckMedia, restoreMedia)

        assertEquals(250L, currentTime)
        assertEquals(listOf(20), mediaDucks)
        assertFalse(d.isActive())
        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf(true, false), calls)
    }

    @Test fun `nesting - one daemon call each way`() = runTest {
        val d = duck()

        val a = d.activate(duckMedia, restoreMedia)
        val b = d.activate(duckMedia, restoreMedia)
        assertEquals(listOf(true), calls)

        d.deactivate(a)
        advanceUntilIdle()
        assertEquals(listOf(true), calls)
        assertTrue(d.isActive())

        d.deactivate(b)
        advanceUntilIdle()
        assertEquals(listOf(true, false), calls)
        assertFalse(d.isActive())
    }

    @Test fun `deactivating the same lease twice releases one level only`() = runTest {
        val d = duck()
        val a = d.activate(duckMedia, restoreMedia)
        val b = d.activate(duckMedia, restoreMedia)

        d.deactivate(a)
        d.deactivate(a)
        advanceUntilIdle()

        assertEquals(listOf(true), calls)
        assertTrue(d.isActive())
        d.deactivate(b)
        advanceUntilIdle()
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `recoverStuck with a marker sends false and clears it on applied`() = runTest {
        backing[AutoVoiceDuck.KEY_MARKER] = true
        duck().recoverStuck()
        advanceUntilIdle()

        assertEquals(listOf(false), calls)
        assertFalse(marker())
    }

    @Test fun `recoverStuck is skipped while a session owns the state`() = runTest {
        val d = duck()
        d.activate(duckMedia, restoreMedia)
        calls.clear()

        d.recoverStuck()
        advanceUntilIdle()

        assertTrue(calls.isEmpty())
        assertTrue(marker())
    }

    @Test fun `recoverStuck without a marker does nothing`() = runTest {
        duck().recoverStuck()
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
    }

    @Test fun `last status is recorded`() = runTest {
        duck().activate(duckMedia, restoreMedia)
        assertEquals("activate APPLIED ok", backing[AutoVoiceDuck.KEY_LAST])
    }

    @Test fun `OFF never asks and never applies, the media duck is plain`() = runTest {
        val off = AutoVoiceDuck.OFF

        assertFalse(off.mayApply())
        val lease = off.activate(duckMedia, restoreMedia)
        assertEquals(listOf(20), mediaDucks)
        off.deactivate(lease)
        off.deactivate(lease)
        off.recoverStuck()
        advanceUntilIdle()
        assertFalse(off.isActive())
        assertEquals(listOf<Int?>(20), mediaRestores)
        assertNull(backing[AutoVoiceDuck.KEY_MARKER])
    }

    // ---- self-healing off ----

    @Test fun `lost off call is retried until it lands and the marker is cleared`() = runTest {
        val d = duckWithOffAnswers(null, null, applied, retries = listOf(2_000L, 5_000L))

        val lease = d.activate(duckMedia, restoreMedia)
        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf(true, false, false, false), calls)
        assertFalse(marker())
        assertTrue("virtual time $currentTime", currentTime >= 7_000L)
    }

    @Test fun `retries stop after the last backoff, the marker stays, a daemon arrival tries again`() = runTest {
        val d = duckWithOffAnswers(null, retries = listOf(2_000L, 5_000L))

        val lease = d.activate(duckMedia, restoreMedia)
        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf(true, false, false, false), calls)
        assertTrue(marker())

        advanceTimeBy(600_000L)
        assertEquals("no more retries on their own", 4, calls.size)

        d.recoverStuck()
        advanceUntilIdle()
        // The arrival restarts the attempts: one call now, then the two scheduled retries.
        assertEquals(listOf(true, false, false, false, false, false, false), calls)
    }

    @Test fun `daemon arrival triggers a retry`() = runTest {
        backing[AutoVoiceDuck.KEY_MARKER] = true
        var answer: AutoVoiceReply? = null
        val d = duck(onOff = { answer }, retries = emptyList())

        d.recoverStuck()
        advanceUntilIdle()
        assertEquals(listOf(false), calls)
        assertTrue(marker())

        answer = applied
        d.recoverStuck()
        advanceUntilIdle()

        assertEquals(listOf(false, false), calls)
        assertFalse(marker())
    }

    @Test fun `unsupported off answer clears the marker, caches the verdict, no retry`() = runTest {
        backing[AutoVoiceDuck.KEY_MARKER] = true
        val d = duck(onOff = { unsupported }, retries = listOf(2_000L))

        d.recoverStuck()
        advanceUntilIdle()

        assertEquals(listOf(false), calls)
        assertFalse(marker())
        assertFalse(d.mayApply())
    }

    @Test fun `a new session cancels the pending retry`() = runTest {
        val d = duckWithOffAnswers(null, retries = listOf(2_000L))

        val first = d.activate(duckMedia, restoreMedia)
        d.deactivate(first)
        runCurrent()
        assertEquals(listOf(true, false), calls)

        val second = d.activate(duckMedia, restoreMedia)
        advanceTimeBy(10_000L)

        assertEquals(listOf(true, false, true), calls)
        assertTrue(d.isActive())
        d.deactivate(second)
    }

    // ---- exact lease pairing ----

    @Test fun `a cancelled nested activate does not release another level`() = runTest {
        val gate = CompletableDeferred<AutoVoiceReply?>()
        val d = duck(onOn = { gate.await() })

        val a = async { d.activate(duckMedia, restoreMedia) }
        runCurrent()
        val b = d.activate(duckMedia, restoreMedia)          // nested: takes a level without waiting
        a.cancelAndJoin()                                     // gives back only its own level
        gate.complete(applied)
        advanceUntilIdle()

        assertTrue(d.isActive())
        assertEquals(listOf(true), calls)
        d.deactivate(b)
        advanceUntilIdle()
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `a cancelled first-level activate gives its level back and the queued off follows`() = runTest {
        val gate = CompletableDeferred<AutoVoiceReply?>()
        val d = duck(onOn = { gate.await() })

        val job = launch { d.activate(duckMedia, restoreMedia) }
        advanceTimeBy(100L)
        job.cancelAndJoin()
        gate.complete(applied)
        advanceUntilIdle()

        assertEquals(listOf(true, false), calls)
        assertFalse(d.isActive())
        assertTrue(mediaDucks.isEmpty())
        assertTrue(mediaRestores.isEmpty())
        assertFalse(marker())

        d.activate(duckMedia, restoreMedia)
        assertEquals(listOf(true, false, true), calls)
    }

    // ---- bounded start, late apply ----

    @Test fun `activate is bounded at 250 ms while a slow off call holds the queue`() = runTest {
        var offs = 0
        val d = duck(onOff = {
            if (offs++ == 0) {
                withContext(NonCancellable) { delay(10_000) }
            }
            applied
        })
        d.deactivate(d.activate(duckMedia, restoreMedia))
        runCurrent()
        assertEquals(listOf(true, false), calls)
        val t0 = currentTime

        val lease = d.activate(duckMedia, restoreMedia)

        assertEquals(250L, currentTime - t0)
        assertEquals(listOf(20), mediaDucks)
        assertFalse(d.isActive())

        // The on call runs once the off call lands, and then wins the media duck back.
        advanceUntilIdle()
        assertEquals(listOf(true, false, true), calls)
        assertTrue(d.isActive())
        assertEquals(listOf<Int?>(20), mediaRestores)
        d.deactivate(lease)
    }

    @Test fun `late apply restores the media duck once, the session end still switches off`() = runTest {
        val d = duck(onOn = { withContext(NonCancellable) { delay(1_000) }; applied })

        val lease = d.activate(duckMedia, restoreMedia)
        assertEquals(listOf(20), mediaDucks)
        assertFalse(d.isActive())

        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(d.isActive())
        assertEquals(listOf<Int?>(20), mediaRestores)

        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf<Int?>(20), mediaRestores)
        assertEquals(listOf(true, false), calls)
        assertFalse(d.isActive())
        assertFalse(marker())
    }

    @Test fun `late apply after the session ended - switched off, media duck given back once`() = runTest {
        val d = duck(onOn = { withContext(NonCancellable) { delay(1_000) }; applied })

        val lease = d.activate(duckMedia, restoreMedia)
        advanceTimeBy(250L)                                   // t = 500
        d.deactivate(lease)
        assertEquals(listOf<Int?>(20), mediaRestores)
        advanceUntilIdle()

        assertEquals(listOf(true, false), calls)
        assertEquals(listOf<Int?>(20), mediaRestores)
        assertFalse(d.isActive())
        assertFalse(marker())
    }

    @Test fun `inner level after a late apply leaves the media alone, the last release switches off`() = runTest {
        val d = duck(onOn = { withContext(NonCancellable) { delay(1_000) }; applied })
        val outer = d.activate(duckMedia, restoreMedia)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(d.isActive())

        val inner = d.activate(duckMedia, restoreMedia)
        assertEquals("only the outer level ducked", listOf(20), mediaDucks)
        d.deactivate(inner)
        advanceUntilIdle()
        assertEquals(listOf(true), calls)

        d.deactivate(outer)
        advanceUntilIdle()
        assertEquals(listOf(true, false), calls)
        assertEquals(listOf<Int?>(20), mediaRestores)
    }
}

package com.bydmate.app.voice

import com.bydmate.app.data.vehicle.AutoVoiceReply
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.split.fakeSplitSharedPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App-side firmware voice duck state machine (#353). */
class AutoVoiceDuckTest {

    private val applied = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_APPLIED, "ok")
    private val unsupported = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, "no method")
    private val failed = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_FAILED, "boom")

    private val backing = mutableMapOf<String, Any?>()
    private val prefs = fakeSplitSharedPrefs(backing)
    private val calls = mutableListOf<Boolean>()

    private fun marker() = backing[AutoVoiceDuck.KEY_MARKER] == true

    /** [onActivate] answers the `true` call, [onDeactivate] every `false` call. */
    private fun duck(
        onActivate: AutoVoiceReply? = applied,
        onDeactivate: AutoVoiceReply? = applied,
        timeoutMs: Long = 250L,
    ) = AutoVoiceDuck(
        setActive = { on -> calls += on; if (on) onActivate else onDeactivate },
        prefs = prefs,
        activateTimeoutMs = timeoutMs,
    )

    @Test fun `applied - activates, marks, and deactivate sends false and clears the marker`() = runTest {
        val d = duck()

        assertTrue(d.mayApply())
        assertTrue(d.activate())
        assertTrue(d.isActive())
        assertTrue(marker())
        assertEquals(listOf(true), calls)

        d.deactivate()

        assertEquals(listOf(true, false), calls)
        assertFalse(d.isActive())
        assertFalse(marker())
    }

    @Test fun `unsupported - false, verdict cached, second activate does not call`() = runTest {
        val d = duck(onActivate = unsupported)

        assertFalse(d.activate())
        assertFalse(d.mayApply())
        assertFalse(marker())
        assertFalse(d.activate())
        d.deactivate()

        assertEquals(listOf(true), calls)
    }

    @Test fun `failed - false, deactivate still sends false and the marker stays on a failed answer`() = runTest {
        val d = duck(onActivate = failed, onDeactivate = failed)

        assertFalse(d.activate())
        assertFalse(d.isActive())
        assertTrue(d.mayApply())
        d.deactivate()

        assertEquals(listOf(true, false), calls)
        assertTrue(marker())
    }

    @Test fun `no answer - false, deactivate still sends false and clears the marker once applied`() = runTest {
        val d = duck(onActivate = null)

        assertFalse(d.activate())
        d.deactivate()

        assertEquals(listOf(true, false), calls)
        assertFalse(marker())
    }

    @Test fun `slow daemon times out into false quickly and the deactivate is still sent`() = runTest {
        val slowCalls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck(
            setActive = { on -> slowCalls += on; if (on) delay(10_000).let { applied } else applied },
            prefs = prefs,
            activateTimeoutMs = 250L,
        )

        assertFalse(d.activate())
        assertTrue("virtual time", testScheduler.currentTime in 250L..1_000L)
        assertTrue(d.mayApply())
        d.deactivate()

        assertEquals(listOf(true, false), slowCalls)
    }

    @Test fun `nesting - one daemon call each way`() = runTest {
        val d = duck()

        assertTrue(d.activate())
        assertTrue(d.activate())
        assertEquals(listOf(true), calls)

        d.deactivate()
        assertEquals(listOf(true), calls)
        assertTrue(d.isActive())

        d.deactivate()
        assertEquals(listOf(true, false), calls)
        assertFalse(d.isActive())
    }

    @Test fun `deactivate without activate is a no-op`() = runTest {
        val d = duck()
        d.deactivate()
        assertTrue(calls.isEmpty())
    }

    @Test fun `recoverStuck with a marker sends false and clears it on applied`() = runTest {
        backing[AutoVoiceDuck.KEY_MARKER] = true
        duck().recoverStuck()

        assertEquals(listOf(false), calls)
        assertFalse(marker())
    }

    @Test fun `recoverStuck keeps the marker on no answer and on failed`() = runTest {
        backing[AutoVoiceDuck.KEY_MARKER] = true
        duck(onDeactivate = null).recoverStuck()
        assertTrue(marker())
        duck(onDeactivate = failed).recoverStuck()
        assertTrue(marker())
        assertEquals(listOf(false, false), calls)
    }

    @Test fun `recoverStuck is skipped while a session owns the state`() = runTest {
        val d = duck()
        d.activate()
        calls.clear()

        d.recoverStuck()

        assertTrue(calls.isEmpty())
        assertTrue(marker())
    }

    @Test fun `recoverStuck without a marker does nothing`() = runTest {
        duck().recoverStuck()
        assertTrue(calls.isEmpty())
    }

    @Test fun `last status is recorded`() = runTest {
        val d = duck()
        d.activate()
        assertEquals("activate APPLIED ok", backing[AutoVoiceDuck.KEY_LAST])
    }

    @Test fun `OFF never asks and never applies`() = runTest {
        val off = AutoVoiceDuck.OFF

        assertFalse(off.mayApply())
        assertFalse(off.activate())
        off.deactivate()
        off.recoverStuck()
        assertFalse(off.isActive())
        assertNull(backing[AutoVoiceDuck.KEY_MARKER])
    }
}

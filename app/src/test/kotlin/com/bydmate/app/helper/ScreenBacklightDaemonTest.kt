package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TX_SET_SCREEN_BACKLIGHT body (#275): the hidden PowerManager call found by name, the status read back. */
class ScreenBacklightDaemonTest {

    /** Stand-in for PowerManager on DiLink 5: lowercase methods, the status follows after [lag] reads. */
    class FakePower(private var status: Int = 1, private val lag: Int = 0, private val deny: Boolean = false) {
        val calls = mutableListOf<String>()
        private var pending: Int? = null
        private var readsLeft = 0
        fun turnBacklightOff(time: Long) = call("off@$time", 0)
        fun turnBacklightOn(time: Long) = call("on@$time", 1)
        fun getPowerScreenStatus(): Int {
            if (pending != null && readsLeft-- <= 0) { status = pending!!; pending = null }
            return status
        }
        private fun call(name: String, target: Int) {
            calls += name
            if (deny) throw SecurityException("requires android.permission.DEVICE_POWER")
            pending = target; readsLeft = lag
        }
    }

    /** DiLink 3.0 / 4.0 spelling. */
    @Suppress("FunctionNaming", "TestFunctionName")
    class PascalPower {
        val calls = mutableListOf<String>()
        private var status = 1
        fun TurnBacklightOff(time: Long) { calls += "off@$time"; status = 0 }
        fun TurnBacklightOn(time: Long) { calls += "on@$time"; status = 1 }
        fun getPowerScreenStatus(): Int = status
    }

    /** The call exists, the status never changes. */
    class DeafPower(private val status: Int = 1) {
        fun turnBacklightOff(@Suppress("UNUSED_PARAMETER") time: Long) = Unit
        fun getPowerScreenStatus(): Int = status
    }

    /** The call exists, the status cannot be read. */
    class BlindPower {
        var called = false
        fun turnBacklightOff(@Suppress("UNUSED_PARAMETER") time: Long) { called = true }
    }

    class NoBacklightPower

    private var pauses = 0
    private fun run(on: Boolean, power: () -> Any) = setScreenBacklight(on, uptimeMs = 4200L, pause = { pauses++ }, powerManager = power)

    @Test fun `off calls the lowercase method with the uptime and confirms by the status`() {
        val power = FakePower(status = 1)
        val result = run(on = false) { power }
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_CONFIRMED, result.outcome)
        assertEquals(0, result.status)
        assertEquals(listOf("off@4200"), power.calls)
        assertEquals("turnBacklightOff reads=0", result.detail)
        assertEquals(0, pauses)
    }

    @Test fun `on calls the mirror method and confirms by status 1`() {
        val power = FakePower(status = 0)
        val result = run(on = true) { power }
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_CONFIRMED, result.outcome)
        assertEquals(1, result.status)
        assertEquals(listOf("on@4200"), power.calls)
        assertEquals("turnBacklightOn reads=1", result.detail)
    }

    @Test fun `firmware with the capitalised spelling is called by that name`() {
        val power = PascalPower()
        val off = run(on = false) { power }
        val on = run(on = true) { power }
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_CONFIRMED, off.outcome)
        assertEquals("TurnBacklightOff reads=0", off.detail)
        assertEquals("TurnBacklightOn reads=1", on.detail)
        assertEquals(listOf("off@4200", "on@4200"), power.calls)
    }

    @Test fun `a status that follows late is waited for`() {
        val result = run(on = false) { FakePower(status = 1, lag = 2) }
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_CONFIRMED, result.outcome)
        assertEquals("turnBacklightOff reads=1,1,0", result.detail)
        assertEquals(2, pauses)
    }

    @Test fun `a status that never follows is not reported as done`() {
        val result = run(on = false) { DeafPower() }
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_NOT_FOLLOWED, result.outcome)
        assertEquals(1, result.status)
        assertEquals("turnBacklightOff reads=1,1,1,1,1", result.detail)
        assertEquals(SCREEN_BACKLIGHT_READS - 1, pauses)
    }

    @Test fun `a status that cannot be read is not reported as done`() {
        val power = BlindPower()
        val result = run(on = false) { power }
        assertTrue(power.called)
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_NOT_FOLLOWED, result.outcome)
        assertEquals(-1, result.status)
        assertEquals("turnBacklightOff reads=NoSuchMethodException", result.detail)
        assertEquals(0, pauses)
    }

    @Test fun `firmware without the method answers absent`() {
        val result = run(on = false) { NoBacklightPower() }
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_ABSENT, result.outcome)
        assertEquals("no turnBacklightOff/TurnBacklightOff", result.detail)
    }

    @Test fun `a refused call answers threw with the cause`() {
        val power = FakePower(deny = true)
        val result = run(on = false) { power }
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_THREW, result.outcome)
        assertEquals("turnBacklightOff SecurityException: requires android.permission.DEVICE_POWER", result.detail)
        assertEquals(listOf("off@4200"), power.calls)
    }

    @Test fun `a power manager that cannot be had answers threw`() {
        val result = run(on = true) { error("daemon has no system Context") }
        assertEquals(HelperBinderProtocol.SCREEN_BACKLIGHT_THREW, result.outcome)
        assertEquals("no PowerManager IllegalStateException: daemon has no system Context", result.detail)
    }

    @Test fun `the verdict is confirmed only by a read of the wanted status`() {
        val p = HelperBinderProtocol
        assertEquals(p.SCREEN_BACKLIGHT_ABSENT, screenBacklightOutcome(methodFound = false, threw = false, lastRead = null, want = 0))
        assertEquals(p.SCREEN_BACKLIGHT_THREW, screenBacklightOutcome(methodFound = true, threw = true, lastRead = 0, want = 0))
        assertEquals(p.SCREEN_BACKLIGHT_CONFIRMED, screenBacklightOutcome(methodFound = true, threw = false, lastRead = 0, want = 0))
        assertEquals(p.SCREEN_BACKLIGHT_CONFIRMED, screenBacklightOutcome(methodFound = true, threw = false, lastRead = 1, want = 1))
        assertEquals(p.SCREEN_BACKLIGHT_NOT_FOLLOWED, screenBacklightOutcome(methodFound = true, threw = false, lastRead = 1, want = 0))
        assertEquals(p.SCREEN_BACKLIGHT_NOT_FOLLOWED, screenBacklightOutcome(methodFound = true, threw = false, lastRead = null, want = 0))
    }
}

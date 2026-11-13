package com.bydmate.app.helper

import com.bydmate.app.helper.HotspotChannelPolicy.AP_DISABLED
import com.bydmate.app.helper.HotspotChannelPolicy.AP_ENABLED
import com.bydmate.app.helper.HotspotChannelPolicy.AP_ENABLING
import com.bydmate.app.helper.HotspotChannelPolicy.AP_FAILED
import com.bydmate.app.helper.HotspotChannelPolicy.Firmware.BROKEN
import com.bydmate.app.helper.HotspotChannelPolicy.Firmware.OK
import com.bydmate.app.helper.HotspotChannelPolicy.Firmware.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotChannelPolicyTest {
    @Test fun acsWithAnEmpty5gListIsBroken() {
        assertEquals(BROKEN, HotspotChannelPolicy.classify(true, ""))
        assertEquals(BROKEN, HotspotChannelPolicy.classify(true, "  "))
    }

    @Test fun aChannelListOrNoAcsIsFine() {
        assertEquals(OK, HotspotChannelPolicy.classify(true, "36,40,44,48,149,153,157,161"))
        assertEquals(OK, HotspotChannelPolicy.classify(false, ""))
        assertEquals(OK, HotspotChannelPolicy.classify(false, null))
        assertEquals(OK, HotspotChannelPolicy.classify(null, "36"))
    }

    @Test fun unreadableOverlayIsUnknown() {
        assertEquals(UNKNOWN, HotspotChannelPolicy.classify(null, null))
        assertEquals(UNKNOWN, HotspotChannelPolicy.classify(true, null))
        assertEquals(UNKNOWN, HotspotChannelPolicy.classify(null, ""))
    }

    @Test fun fiveGhzOnAutoChannelIsPinnedOnBrokenFirmware() {
        assertTrue(HotspotChannelPolicy.needsPin(mapOf(2 to 0), BROKEN, AP_DISABLED))
    }

    @Test fun workingConfigsAreLeftAlone() {
        for (fw in listOf(BROKEN, UNKNOWN)) {
            assertFalse(HotspotChannelPolicy.needsPin(mapOf(1 to 0), fw, AP_FAILED)) // 2.4 GHz auto works
            assertFalse(HotspotChannelPolicy.needsPin(mapOf(2 to 36), fw, AP_FAILED))
            assertFalse(HotspotChannelPolicy.needsPin(mapOf(2 to 149), fw, AP_FAILED))
            assertFalse(HotspotChannelPolicy.needsPin(mapOf(1 to 0, 2 to 0), fw, AP_FAILED)) // dual band
            assertFalse(HotspotChannelPolicy.needsPin(emptyMap(), fw, AP_FAILED))
        }
    }

    @Test fun okFirmwareIsNeverTouched() {
        assertFalse(HotspotChannelPolicy.needsPin(mapOf(2 to 0), OK, AP_FAILED))
    }

    @Test fun unknownFirmwareIsPinnedOnlyAfterAFailedStart() {
        assertFalse(HotspotChannelPolicy.needsPin(mapOf(2 to 0), UNKNOWN, AP_DISABLED))
        assertFalse(HotspotChannelPolicy.needsPin(mapOf(2 to 0), UNKNOWN, AP_ENABLED))
        assertTrue(HotspotChannelPolicy.needsPin(mapOf(2 to 0), UNKNOWN, AP_FAILED))
    }

    @Test fun hotspotThatWasOnIsStartedAgainAfterThePin() {
        val t = HotspotRestartTracker()
        assertFalse(t.tick(0, AP_ENABLED, pinned = false))
        assertFalse(t.tick(2_000, AP_DISABLED, pinned = true)) // Band menu: {2=0}, stop, start failed
        assertFalse(t.tick(4_000, AP_DISABLED, pinned = false)) // settling
        assertTrue(t.tick(6_000, AP_DISABLED, pinned = false))
        assertFalse(t.tick(8_000, AP_DISABLED, pinned = false)) // one start per pin
    }

    @Test fun failedStartCountsAsWanted() {
        val t = HotspotRestartTracker()
        assertFalse(t.tick(0, AP_FAILED, pinned = true))
        assertTrue(t.tick(4_000, AP_FAILED, pinned = false))
    }

    @Test fun hotspotThatWasOffStaysOff() {
        val t = HotspotRestartTracker()
        assertFalse(t.tick(0, AP_DISABLED, pinned = false))
        assertFalse(t.tick(60_000, AP_DISABLED, pinned = true))
        assertFalse(t.tick(64_000, AP_DISABLED, pinned = false))
        assertFalse(t.tick(66_000, AP_DISABLED, pinned = false))
    }

    @Test fun onLongAgoIsNotWanted() {
        val t = HotspotRestartTracker()
        t.tick(0, AP_ENABLED, pinned = false)
        assertFalse(t.tick(60_000, AP_DISABLED, pinned = true))
        assertFalse(t.tick(64_000, AP_DISABLED, pinned = false))
    }

    @Test fun hotspotThatCameUpByItselfIsNotStartedTwice() {
        val t = HotspotRestartTracker()
        t.tick(0, AP_ENABLED, pinned = false)
        t.tick(2_000, AP_DISABLED, pinned = true)
        assertFalse(t.tick(6_000, AP_ENABLED, pinned = false))
    }

    @Test fun startsAreCapped() {
        val t = HotspotRestartTracker()
        var now = 0L
        var starts = 0
        repeat(6) {
            t.tick(now, AP_ENABLED, pinned = false)
            t.tick(now + 2_000, AP_DISABLED, pinned = true)
            if (t.tick(now + 6_000, AP_DISABLED, pinned = false)) starts++
            now += 20_000
        }
        assertEquals(3, starts)
        now += 600_000 // the cap lifts after the window
        t.tick(now, AP_ENABLED, pinned = false)
        t.tick(now + 2_000, AP_DISABLED, pinned = true)
        assertTrue(t.tick(now + 6_000, AP_DISABLED, pinned = false))
    }

    @Test fun failedStateLeftOverFromTheBrokenConfigIsNotAChannel36Failure() {
        val o = PinOutcomeTracker()
        o.pinned() // pinned while the {2=0} start is still FAILED
        assertFalse(o.failed(2_000, AP_FAILED))
        assertFalse(o.failed(4_000, AP_FAILED))
    }

    @Test fun channel36FailingAfterOurRestartFallsBack() {
        val o = PinOutcomeTracker()
        o.pinned()
        assertFalse(o.failed(2_000, AP_FAILED))
        o.started(4_000)
        assertFalse(o.failed(6_000, AP_FAILED)) // too soon to tell
        assertTrue(o.failed(10_000, AP_FAILED))
        assertFalse(o.failed(12_000, AP_FAILED)) // once
    }

    @Test fun channel36FailingOnALaterStartFallsBack() {
        val o = PinOutcomeTracker()
        o.pinned()
        assertFalse(o.failed(2_000, AP_DISABLED))
        assertFalse(o.failed(60_000, AP_ENABLING)) // the user switches it on
        assertTrue(o.failed(62_000, AP_FAILED))
    }

    @Test fun channel36ThatComesUpIsKept() {
        val o = PinOutcomeTracker()
        o.pinned()
        o.started(4_000)
        assertFalse(o.failed(6_000, AP_ENABLED))
        assertFalse(o.failed(600_000, AP_FAILED)) // a later unrelated failure doesn't move the band
    }

    @Test fun aChangedConfigEndsTheWatch() {
        val o = PinOutcomeTracker()
        o.pinned()
        o.failed(2_000, AP_DISABLED)
        o.clear()
        assertFalse(o.failed(4_000, AP_FAILED))
    }

    @Test fun plannerPinsThenFallsBackThenGoesStraightTo24() {
        val p = HotspotFixPlanner(BROKEN)
        assertEquals(HotspotFixPlanner.Action.CHANNEL_36, p.next(0, mapOf(2 to 0), AP_FAILED))
        p.saved(HotspotFixPlanner.Action.CHANNEL_36, ok = true)
        assertEquals(HotspotFixPlanner.Action.NONE, p.next(2_000, mapOf(2 to 36), AP_FAILED))
        p.started(4_000)
        assertEquals(HotspotFixPlanner.Action.BAND_2GHZ_AFTER_36_FAILED, p.next(10_000, mapOf(2 to 36), AP_FAILED))
        assertEquals(HotspotFixPlanner.Action.NONE, p.next(12_000, mapOf(1 to 0), AP_ENABLED))
        // the Band menu picks 5 GHz again: straight to 2.4 GHz
        assertEquals(HotspotFixPlanner.Action.BAND_2GHZ, p.next(60_000, mapOf(2 to 0), AP_FAILED))
    }

    @Test fun plannerKeepsAWorkingChannel36() {
        val p = HotspotFixPlanner(BROKEN)
        p.next(0, mapOf(2 to 0), AP_FAILED)
        p.saved(HotspotFixPlanner.Action.CHANNEL_36, ok = true)
        p.started(4_000)
        assertEquals(HotspotFixPlanner.Action.NONE, p.next(6_000, mapOf(2 to 36), AP_ENABLED))
        assertEquals(HotspotFixPlanner.Action.NONE, p.next(600_000, mapOf(2 to 36), AP_FAILED))
    }

    @Test fun plannerLeavesAUserChosenChannel36Alone() {
        val p = HotspotFixPlanner(BROKEN) // never pinned by us
        assertEquals(HotspotFixPlanner.Action.NONE, p.next(0, mapOf(2 to 36), AP_FAILED))
        assertEquals(HotspotFixPlanner.Action.NONE, p.next(10_000, mapOf(2 to 36), AP_FAILED))
    }
}

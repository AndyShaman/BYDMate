package com.bydmate.app

import com.bydmate.app.split.SplitStageActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hotfix 390-1: the split-screen backdrop must not count as "the user opened BYDMate".
 *
 * The backdrop is an ordinary Activity of our app and stays resumed for the whole split session,
 * so counting it detached the widget for as long as the split lived — and the second tap that
 * closes the split became impossible (on-car 390).
 */
class ForegroundActivityCounterTest {

    private class FakeMainActivity
    private class FakeSettingsActivity

    @Test fun `split backdrop does not count as foreground`() {
        assertFalse(countsAsForegroundActivity(SplitStageActivity::class.java))
    }

    @Test fun `ordinary activities still count as foreground`() {
        assertTrue(countsAsForegroundActivity(FakeMainActivity::class.java))
        assertTrue(countsAsForegroundActivity(FakeSettingsActivity::class.java))
    }

    @Test fun `backdrop resume does not report a foreground edge`() {
        val counter = ForegroundActivityCounter()

        assertFalse("Backdrop must not foreground the app", counter.onResumed(SplitStageActivity::class.java))
        assertEquals(0, counter.resumedCount)
    }

    @Test fun `backdrop resume-pause pair leaves the counter untouched`() {
        val counter = ForegroundActivityCounter()

        // A real screen is open: the app is foregrounded.
        assertTrue(counter.onResumed(FakeMainActivity::class.java))
        // Split starts on top: backdrop resumes and later pauses. Neither may move the counter.
        assertFalse(counter.onResumed(SplitStageActivity::class.java))
        assertEquals(1, counter.resumedCount)
        assertFalse(counter.onPaused(SplitStageActivity::class.java))
        assertEquals(1, counter.resumedCount)
        // Closing the real screen is still the background edge.
        assertTrue(counter.onPaused(FakeMainActivity::class.java))
        assertEquals(0, counter.resumedCount)
    }

    @Test fun `backdrop pause alone cannot drive the counter negative`() {
        val counter = ForegroundActivityCounter()

        assertFalse(counter.onPaused(SplitStageActivity::class.java))
        assertEquals(0, counter.resumedCount)
        // The next real activity must still produce a foreground edge.
        assertTrue(counter.onResumed(FakeMainActivity::class.java))
    }

    @Test fun `opening a real screen over a live split still hides the widget`() {
        val counter = ForegroundActivityCounter()

        // Split session running: only the backdrop is resumed.
        counter.onResumed(SplitStageActivity::class.java)
        // User opens BYDMate on top of the split.
        assertTrue("MainActivity over a split must foreground the app",
            counter.onResumed(FakeMainActivity::class.java))
        assertTrue("Leaving it must background the app again",
            counter.onPaused(FakeMainActivity::class.java))
    }
}

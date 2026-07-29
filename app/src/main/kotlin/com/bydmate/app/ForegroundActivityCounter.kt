package com.bydmate.app

import com.bydmate.app.split.SplitStageActivity

/**
 * Whether resuming [activityClass] means "the user opened BYDMate" for widget purposes.
 *
 * [SplitStageActivity] is excluded: it is the split-screen backdrop, a service window that stays
 * resumed for the whole session. Counting it foregrounded the app the moment a split started, the
 * widget detached, and it never came back — the backdrop never pauses while the session lives
 * (on-car 390). Opening a real screen on top of a live split still hides the widget: the resumed
 * activity is then MainActivity, not the backdrop.
 */
internal fun countsAsForegroundActivity(activityClass: Class<*>): Boolean =
    activityClass != SplitStageActivity::class.java

/**
 * Counts resumed activities that represent real app UI, and reports the foreground/background
 * edges. Activities rejected by [countsAsForegroundActivity] are ignored symmetrically in both
 * callbacks so the counter cannot drift.
 */
internal class ForegroundActivityCounter {
    var resumedCount = 0
        private set

    /** @return true when this resume moved the app into the foreground. */
    fun onResumed(activityClass: Class<*>): Boolean {
        if (!countsAsForegroundActivity(activityClass)) return false
        resumedCount++
        return resumedCount == 1
    }

    /** @return true when this pause moved the app into the background. */
    fun onPaused(activityClass: Class<*>): Boolean {
        if (!countsAsForegroundActivity(activityClass)) return false
        resumedCount--
        if (resumedCount > 0) return false
        resumedCount = 0
        return true
    }
}

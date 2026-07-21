package com.bydmate.app.media

import android.util.Log
import com.bydmate.app.navdata.NavGuidanceHub
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Single sequential lane for rich notification work (spec §4): rich posts and
 * deactivate checks run on ONE daemon thread in callback order, so the render
 * wait (up to 3s) never blocks a binder thread and post/removal ordering holds
 * by FIFO. Enqueue-first contract: the lane task is enqueued BEFORE the caller
 * runs the synchronous legacy step on the binder thread.
 */
class NaviNotificationLane(
    private val executor: LaneExecutor = ThreadLaneExecutor(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "NaviNotifLane"
        const val REMOVE_DEBOUNCE_MS = 3000L
        const val DEACTIVATE_CHECK_MS = 3000L
    }

    /** Seam for JVM tests; production uses ThreadLaneExecutor. */
    interface LaneExecutor {
        @Throws(RejectedExecutionException::class)
        fun execute(task: Runnable)

        @Throws(RejectedExecutionException::class)
        fun schedule(delayMs: Long, task: Runnable)

        fun shutdownNow()
    }

    class ThreadLaneExecutor : LaneExecutor {
        private val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "navi-notif-lane").apply { isDaemon = true }
        }

        override fun execute(task: Runnable) {
            exec.execute(task)
        }

        override fun schedule(delayMs: Long, task: Runnable) {
            exec.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        }

        override fun shutdownNow() {
            exec.shutdownNow()
        }
    }

    @Volatile private var removePostedMs = 0L

    /**
     * Post flow, binder thread. Rich task enters the lane FIRST (enqueue-first),
     * then the legacy step runs synchronously. A rejected enqueue (callback racing
     * onDestroy) drops ONLY the lane task; a failing legacy step never affects the
     * lane; a failing rich task never kills the lane thread.
     */
    fun onPosted(richTask: Runnable, legacyStep: Runnable) {
        try {
            executor.execute(Runnable { runCatching { richTask.run() } })
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "lane rejected rich task (shutting down)")
        }
        runCatching { legacyStep.run() }
    }

    /** Removal flow, binder thread: debounced deactivate check enqueued first,
     *  then the synchronous legacy clear. */
    fun onRemoved(legacyClear: Runnable) {
        val now = nowMs()
        if (now - removePostedMs >= REMOVE_DEBOUNCE_MS) {
            removePostedMs = now
            scheduleDeactivateCheck()
        }
        runCatching { legacyClear.run() }
    }

    /** A guidance post reached the hub - cancel the pending removal grace (donor). */
    fun markGuidancePosted() {
        removePostedMs = 0L
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun scheduleDeactivateCheck() {
        try {
            executor.schedule(
                DEACTIVATE_CHECK_MS,
                Runnable {
                    runCatching {
                        if (removePostedMs == 0L) return@runCatching
                        val s = NavGuidanceHub.snapshot(nowMs())
                        // Donor detail: inactive hub stops the loop WITHOUT resetting
                        // removePostedMs - harmless, next post resets it anyway.
                        if (!s.active) return@runCatching
                        if (nowMs() - s.lastUpdateMs >= NavGuidanceHub.ACTIVE_TIMEOUT_MS) {
                            NavGuidanceHub.deactivateFromNotificationGrace(nowMs())
                            removePostedMs = 0L
                        } else {
                            scheduleDeactivateCheck()
                        }
                    }
                },
            )
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "lane rejected deactivate check (shutting down)")
        }
    }
}

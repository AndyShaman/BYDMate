package com.bydmate.app.media

import com.bydmate.app.navdata.NavGuidanceHub
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NaviNotificationLaneTest {

    private class FakeExecutor : NaviNotificationLane.LaneExecutor {
        val queue = mutableListOf<Runnable>()
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        var rejectAll = false
        val events = mutableListOf<String>()
        override fun execute(task: Runnable) {
            if (rejectAll) throw RejectedExecutionException()
            events.add("enqueue")
            queue.add(task)
        }
        override fun schedule(delayMs: Long, task: Runnable) {
            if (rejectAll) throw RejectedExecutionException()
            events.add("schedule")
            scheduled.add(delayMs to task)
        }
        override fun shutdownNow() { rejectAll = true }
        fun runQueued() { while (queue.isNotEmpty()) queue.removeAt(0).run() }
        fun runNextScheduled() { scheduled.removeAt(0).second.run() }
    }

    private var clock = 100_000L
    private lateinit var exec: FakeExecutor
    private lateinit var lane: NaviNotificationLane

    @Before
    fun setUp() {
        NavGuidanceHub.reset()
        clock = 100_000L
        exec = FakeExecutor()
        lane = NaviNotificationLane(exec) { clock }
    }

    @Test
    fun `rich task is enqueued before legacy step runs`() {
        lane.onPosted(Runnable { }, Runnable { exec.events.add("legacy") })
        assertEquals(listOf("enqueue", "legacy"), exec.events)
    }

    @Test
    fun `legacy step runs even when enqueue is rejected`() {
        exec.rejectAll = true
        var legacyRan = false
        lane.onPosted(Runnable { }, Runnable { legacyRan = true })
        assertTrue(legacyRan)
    }

    @Test
    fun `legacy failure does not affect lane task`() {
        var richRan = false
        lane.onPosted(Runnable { richRan = true }, Runnable { error("legacy boom") })
        exec.runQueued()
        assertTrue(richRan)
    }

    @Test
    fun `rich task exception is swallowed`() {
        lane.onPosted(Runnable { error("rich boom") }, Runnable { })
        exec.runQueued() // must not throw
    }

    @Test
    fun `removals are debounced`() {
        lane.onRemoved(Runnable { })
        clock += 1000
        lane.onRemoved(Runnable { })
        assertEquals(1, exec.scheduled.size)
        clock += NaviNotificationLane.REMOVE_DEBOUNCE_MS
        lane.onRemoved(Runnable { })
        assertEquals(2, exec.scheduled.size)
    }

    @Test
    fun `legacy clear runs synchronously on removal`() {
        var cleared = false
        lane.onRemoved(Runnable { cleared = true })
        assertTrue(cleared)
    }

    @Test
    fun `removal during legacy step lands after the rich task`() {
        lane.onPosted(
            Runnable { },
            Runnable { lane.onRemoved(Runnable { }) },  // removal arrives mid-legacy-step
        )
        // Rich task entered the lane BEFORE the removal's deactivate check (spec R3-1).
        assertEquals(listOf("enqueue", "schedule"), exec.events)
    }

    @Test
    fun `removal after shutdown does not throw and legacy clear still runs`() {
        exec.rejectAll = true
        var cleared = false
        lane.onRemoved(Runnable { cleared = true })
        assertTrue(cleared)
    }

    @Test
    fun `deactivate check reschedules while hub is fresh`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(road = "x"), nowMs = clock)
        lane.onRemoved(Runnable { })
        clock += 5000
        exec.runNextScheduled()
        assertEquals(1, exec.scheduled.size) // re-scheduled
        assertTrue(NavGuidanceHub.snapshot(clock).active)
    }

    @Test
    fun `repost cancels pending deactivation`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(road = "x"), nowMs = clock)
        lane.onRemoved(Runnable { })
        lane.markGuidancePosted()
        exec.runNextScheduled()
        assertEquals(0, exec.scheduled.size) // no re-schedule
        assertTrue(NavGuidanceHub.snapshot(clock).active)
    }

    @Test
    fun `deactivate check ends guidance after grace expiry`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(road = "x"), nowMs = clock)
        lane.onRemoved(Runnable { })
        clock += 91_000
        exec.runNextScheduled()
        assertFalse(NavGuidanceHub.snapshot(clock).active)
        assertEquals(0, exec.scheduled.size)
    }
}

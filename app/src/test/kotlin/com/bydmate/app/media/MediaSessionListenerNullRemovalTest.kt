package com.bydmate.app.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #301, Song Plus (Android 10): the framework called onNotificationRemoved without a
 *  notification (NotificationListenerService.java:391) and the process died five times,
 *  2026-09-07..21: "Parameter specified as non-null is null: method
 *  ...MediaSessionListenerService.onNotificationRemoved, parameter sbn". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MediaSessionListenerNullRemovalTest {

    private class RecordingExecutor : NaviNotificationLane.LaneExecutor {
        val executed = mutableListOf<Runnable>()
        val scheduled = mutableListOf<Runnable>()
        override fun execute(task: Runnable) { executed.add(task) }
        override fun schedule(delayMs: Long, task: Runnable) { scheduled.add(task) }
        override fun shutdownNow() = Unit
    }

    private lateinit var service: MediaSessionListenerService
    private lateinit var exec: RecordingExecutor

    @Before
    fun setUp() {
        NavGuidanceHub.reset()
        exec = RecordingExecutor()
        service = MediaSessionListenerService()
        service.lane = NaviNotificationLane(exec)
    }

    @Test
    fun `a removal the framework reports without a notification is ignored`() {
        // The call as the framework makes it: Kotlin code cannot pass the null itself.
        val removed = NotificationListenerService::class.java
            .getMethod("onNotificationRemoved", StatusBarNotification::class.java)
        val thrown = runCatching { removed.invoke(service, *arrayOfNulls<Any>(1)) }.exceptionOrNull()
        assertTrue("the callback threw: ${thrown?.cause ?: thrown}", thrown == null)
        assertTrue(exec.executed.isEmpty() && exec.scheduled.isEmpty())
    }
}

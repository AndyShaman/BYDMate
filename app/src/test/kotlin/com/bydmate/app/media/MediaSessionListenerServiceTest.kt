package com.bydmate.app.media

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32])
class MediaSessionListenerServiceTest {

    private class RecordingExecutor : NaviNotificationLane.LaneExecutor {
        val executed = mutableListOf<Runnable>()
        val scheduled = mutableListOf<Runnable>()
        override fun execute(task: Runnable) { executed.add(task) }
        override fun schedule(delayMs: Long, task: Runnable) { scheduled.add(task) }
        override fun shutdownNow() {}
    }

    private lateinit var service: MediaSessionListenerService
    private lateinit var exec: RecordingExecutor

    @Before
    fun setUp() {
        NavGuidanceHub.reset()
        NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
        exec = RecordingExecutor()
        service = MediaSessionListenerService()
        service.lane = NaviNotificationLane(exec)
    }

    private fun sbn(pkg: String, n: Notification) = StatusBarNotification(
        pkg, pkg, 1, null, 0, 0, 0, n, Process.myUserHandle(), System.currentTimeMillis())

    @Test
    fun `guidance source notification enqueues rich task and feeds legacy`() {
        val n = Notification()
        n.extras.putString(Notification.EXTRA_TITLE, "500 м")
        service.onNotificationPosted(sbn("ru.yandex.yandexnavi", n))
        assertEquals(1, exec.executed.size)
        assertNotNull(NaviRouteHolder.latest)
    }

    @Test
    fun `media notification is a full stop for both channels`() {
        val n = Notification()
        n.category = Notification.CATEGORY_TRANSPORT
        service.onNotificationPosted(sbn("ru.yandex.yandexnavi", n))
        assertEquals(0, exec.executed.size)
        assertNull(NaviRouteHolder.latest)
    }

    @Test
    fun `foreign package ignored`() {
        service.onNotificationPosted(sbn("com.spotify.music", Notification()))
        assertEquals(0, exec.executed.size)
        assertNull(NaviRouteHolder.latest)
    }

    @Test
    fun `maps package accepted`() {
        service.onNotificationPosted(sbn("ru.yandex.yandexmaps", Notification()))
        assertEquals(1, exec.executed.size)
    }

    @Test
    fun `removal schedules deactivate check and clears holder`() {
        service.onNotificationPosted(sbn("ru.yandex.yandexnavi", Notification()))
        service.onNotificationRemoved(sbn("ru.yandex.yandexnavi", Notification()))
        assertEquals(1, exec.scheduled.size)
        assertNull(NaviRouteHolder.latest)
    }

    @Test
    fun `foreign package removal does not schedule deactivate check`() {
        service.onNotificationRemoved(sbn("com.spotify.music", Notification()))
        assertEquals(0, exec.scheduled.size)
    }

    @Test
    fun `media removal is not filtered - deactivate check still scheduled`() {
        val n = Notification().apply { category = Notification.CATEGORY_TRANSPORT }
        service.onNotificationRemoved(sbn("ru.yandex.yandexnavi", n))
        assertEquals(1, exec.scheduled.size)
    }

    @Test
    fun `null lane after teardown - legacy update and clear still run`() {
        service.lane = null
        val n = Notification()
        n.extras.putString(Notification.EXTRA_TITLE, "500 м")
        service.onNotificationPosted(sbn("ru.yandex.yandexnavi", n))
        assertNotNull(NaviRouteHolder.latest)
        service.onNotificationRemoved(sbn("ru.yandex.yandexnavi", n))
        assertNull(NaviRouteHolder.latest)
    }

    // -- unknown-maneuver log (lane thread) --

    private fun postAndRun(pkg: String, title: String, text: String): List<String> {
        val lines = mutableListOf<String>()
        service.unknownManeuverSink = { lines.add(it) }
        val n = Notification()
        n.extras.putString(Notification.EXTRA_TITLE, title)
        n.extras.putString(Notification.EXTRA_TEXT, text)
        service.onNotificationPosted(sbn(pkg, n))
        exec.executed.forEach { it.run() }
        exec.executed.clear()
        return lines
    }

    @Test
    fun `extras post without a recognised maneuver logs the icon name once and no street`() {
        val first = postAndRun("ru.yandex.yandexnavi", "500 м", "Тверская")
        assertEquals(listOf("nav maneuver unknown [notification extras]: icon=\"\""), first)
        assertTrue(postAndRun("ru.yandex.yandexnavi", "450 м", "Тверская").isEmpty())
    }

    @Test
    fun `extras post with a recognised maneuver logs nothing`() {
        assertTrue(postAndRun("ru.yandex.yandexnavi", "500 м", "Поверните направо").isEmpty())
    }

    @Test
    fun `extras post without a distance logs nothing`() {
        assertTrue(postAndRun("ru.yandex.yandexnavi", "Тверская", "").isEmpty())
    }

    @Test
    fun `maps maneuver dropped for the hub's known one is no unknown maneuver`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300), NavGuidanceHub.Source.A11Y)
        assertTrue(postAndRun("ru.yandex.yandexmaps", "500 м", "Тверская").isEmpty())
    }

    @Test
    fun `isMediaNotification detects all three markers`() {
        assertFalse(MediaSessionListenerService.isMediaNotification(Notification()))
        val cat = Notification().apply { category = Notification.CATEGORY_TRANSPORT }
        assertTrue(MediaSessionListenerService.isMediaNotification(cat))
        val session = Notification().apply {
            extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, null)
        }
        assertTrue(MediaSessionListenerService.isMediaNotification(session))
        val template = Notification().apply {
            extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$MediaStyle")
        }
        assertTrue(MediaSessionListenerService.isMediaNotification(template))
    }
}

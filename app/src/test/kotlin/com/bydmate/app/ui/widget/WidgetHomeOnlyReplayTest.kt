package com.bydmate.app.ui.widget

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.camera.CameraStateMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Field dumps replayed through the foreground merge and the widget rule: every
 * `screen foreground` line goes in as the hint or poll it was, and the hide reason is read after
 * each one, the way the widget reads it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WidgetHomeOnlyReplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val monitor = CameraStateMonitor(context)

    private data class Line(val ts: Long, val pkg: String, val cls: String?, val a11y: Boolean)

    private fun lines(name: String): List<Line> {
        val text = requireNotNull(javaClass.classLoader?.getResource("widget/$name")).readText()
        var dayStart = BASE_MS
        var previous = 0L
        return text.lines().mapNotNull { FOREGROUND.find(it) }.map { m ->
            val t = m.groupValues.subList(1, 5).map { it.toLong() }
            val ofDay = ((t[0] * 60 + t[1]) * 60 + t[2]) * 1_000L + t[3]
            // The trace spans midnight: a time earlier than the last one is the next day.
            if (ofDay < previous) dayStart += DAY_MS
            previous = ofDay
            Line(dayStart + ofDay, m.groupValues[5], m.groups[6]?.value, m.groupValues[7] == "a11y")
        }
    }

    /** (package of the line, hide reason after it), in order. */
    private fun replay(name: String, homeOnly: Boolean, presence: (String) -> Boolean?): List<Pair<String, String?>> {
        val lines = lines(name)
        val pm = shadowOf(context.packageManager)
        lines.filter { it.cls != null }.forEach { pm.addActivityIfNotPresent(ComponentName(it.pkg, it.cls!!)) }
        var now = 0L
        monitor.clock = { now }
        monitor.mainDisplayPresence = presence
        return lines.map { line ->
            now = line.ts
            monitor.recheckForegroundCandidate()
            if (line.a11y) monitor.onForegroundHint(line.pkg, line.cls) else monitor.onPolled(line.pkg, line.ts)
            monitor.publish()
            line.pkg to WidgetController.hideReason(
                cameraActive = monitor.active.value, youtubeForeground = monitor.youtubeForeground.value,
                hideOnYoutube = false, foregroundPkg = monitor.foregroundPackage.value,
                hideInApps = HIDE_LIST, homeOnly = homeOnly, homePackages = HOME)
        }
    }

    // #303, Leopard 8 / DiLink 5.1: the cluster's projection stub and ADS alarm view resume on
    // their own displays every few seconds; the idle launcher on display 0 emits nothing.
    @Test
    fun `issue 303 - cluster-side packages never hide the widget on the launcher`() {
        val reasons = replay("issue303-leopard8-cluster-foreground.txt", homeOnly = true) { it !in CLUSTER_SIDE }
            .map { it.second }
        assertFalse(reasons.toString(), "not_home:com.example.amapservice" in reasons)
        assertFalse(reasons.toString(), "not_home:com.huawei.hibaic.adshmiic" in reasons)
        // The AVM camera on display 0 is a real app over the launcher: that hide stays.
        assertTrue(reasons.toString(), "not_home:com.huawei.maps.car.app" in reasons)
        assertNull(reasons.last())
    }

    // Same log, home-only off: FM radio or OpenWiFi on screen, a cluster package resumes, and the
    // widget used to come back over the listed app.
    @Test
    fun `issue 303 - cluster-side packages never bring the widget back over a listed app`() {
        val steps = replay("issue303-leopard8-hide-list.txt", homeOnly = false) { it !in CLUSTER_SIDE }
        val reasons = steps.map { it.second }
        assertTrue(reasons.toString(), "app:ru.fmplay" in reasons)
        assertTrue(reasons.toString(), "app:com.openwifi" in reasons)
        var checked = 0
        steps.zipWithNext().forEach { (before, after) ->
            if (after.first in CLUSTER_SIDE && before.second?.startsWith("app:") == true) {
                checked++
                assertTrue("${after.first} after ${before.second}: ${after.second}", after.second == before.second)
            }
        }
        assertTrue("no cluster event followed a listed app", checked > 0)
    }

    // DiLink 5.0 / Android 12: YouTube and Yandex Maps reach us only through the poll, their a11y
    // hints are FrameLayout windows. On a car without other displays nothing changes.
    @Test
    fun `poll-only apps still hide the widget where the main display is not refused`() {
        val reasons = replay("dilink5-poll-only-apps-198.txt", homeOnly = true) { true }.map { it.second }
        assertTrue(reasons.toString(), "not_home:app.morphe.android.youtube" in reasons)
        assertTrue(reasons.toString(), "not_home:ru.yandex.yandexmaps" in reasons)
        assertTrue(reasons.toString(), "camera" in reasons)
    }

    private companion object {
        val FOREGROUND = Regex(
            """^(\d\d):(\d\d):(\d\d)\.(\d{3}) screen foreground pkg=(\S+)(?: class=(\S+))? src=(a11y|poll)""")
        const val BASE_MS = 1_760_000_000_000L
        const val DAY_MS = 86_400_000L
        val HOME = setOf("com.android.launcher3", "com.byd.mycar", "com.bydmate.app")
        val HIDE_LIST = setOf("ru.fmplay", "com.openwifi")
        val CLUSTER_SIDE = setOf("com.example.amapservice", "com.huawei.hibaic.adshmiic")
    }
}

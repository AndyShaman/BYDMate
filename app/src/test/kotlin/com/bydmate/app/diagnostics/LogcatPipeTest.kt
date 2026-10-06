package com.bydmate.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatPipeTest {

    private fun am(level: Char, text: String) = "10-05 15:56:25.574 $level/ActivityManager( 1116): $text"

    @Test fun `lines of other tags are always kept`() {
        assertTrue(LogcatLineFilter.keep("10-05 15:56:25.574 I/HudPushLoop( 4321): frame sent rc=0"))
        assertTrue(LogcatLineFilter.keep("--------- beginning of main"))
    }

    @Test fun `foreign ActivityManager noise is dropped`() {
        assertFalse(LogcatLineFilter.keep(am('W', "Unable to start service Intent { act=com.byd.autovoice.ttsshow pkg=com.byd.autovoice } U=0: not found")))
        assertFalse(LogcatLineFilter.keep(am('W', "Sending non-protected broadcast com.byd.x from system 1234:com.byd.y/1000 pkg com.byd.y")))
        assertFalse(LogcatLineFilter.keep(am('W', "\tat com.android.server.am.ActivityManagerService.broadcastIntentLocked(ActivityManagerService.java:15957)")))
        assertFalse(LogcatLineFilter.keep(am('I', "Start proc 4242:com.byd.weather/u0a12 for service")))
    }

    @Test fun `our process and the navigators are kept`() {
        assertTrue(LogcatLineFilter.keep(am('I', "Start proc 4242:com.bydmate.app/u0a99 for service {com.bydmate.app/com.bydmate.app.service.TrackingService}")))
        assertTrue(LogcatLineFilter.keep(am('I', "Start proc 5151:ru.yandex.yandexnavi/u0a77 for activity")))
        assertTrue(LogcatLineFilter.keep(am('I', "Displayed ru.yandex.yandexmaps/.MainActivity: +1s20ms")))
        assertTrue(LogcatLineFilter.keep(am('I', "Start proc 6262:ru.dublgis.dgismobile/u0a78 for activity")))
    }

    @Test fun `kills, force stops, deaths and ANRs are kept for any package`() {
        assertTrue(LogcatLineFilter.keep(am('I', "Killing 3030:com.byd.weather/u0a12 (adj 900): empty #17")))
        assertTrue(LogcatLineFilter.keep(am('I', "Force stopping com.byd.avc appid=1000 user=0: from pid 2020")))
        assertTrue(LogcatLineFilter.keep(am('I', "Process com.byd.avc (pid 2020) has died: fore TOP ")))
        assertTrue(LogcatLineFilter.keep(am('E', "ANR in com.byd.mediacenter")))
    }

    @Test fun `flush waits for the line batch`() {
        val policy = PipeFlushPolicy(maxLines = 3, maxDelayMs = 1_000L)

        assertFalse(policy.onLine(0L))
        assertFalse(policy.onLine(10L))
        assertTrue(policy.onLine(20L))
        policy.flushed()
        assertFalse(policy.onLine(30L))
    }

    @Test fun `flush comes after the delay since the oldest waiting line`() {
        val policy = PipeFlushPolicy(maxLines = 64, maxDelayMs = 1_000L)

        assertFalse(policy.onLine(0L))
        assertFalse(policy.onLine(999L))
        assertTrue(policy.onLine(1_000L))
    }

    @Test fun `a quiet logcat still gets its waiting lines flushed`() {
        val policy = PipeFlushPolicy(maxLines = 64, maxDelayMs = 1_000L)

        assertFalse(policy.dueIdle(5_000L))
        policy.onLine(5_000L)
        assertFalse(policy.dueIdle(5_500L))
        assertTrue(policy.dueIdle(6_000L))
        policy.flushed()
        assertFalse(policy.dueIdle(9_000L))
    }
}

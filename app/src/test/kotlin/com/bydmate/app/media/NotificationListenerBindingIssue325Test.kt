package com.bydmate.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #325 (Han 2022, DiLink 4.0, Navigator 24.3.1, no gateway): after a car start the notification
 * listener stays approved but never connects unless the a11y force-stop recovery happens to run.
 * Navigator 24.3.1 leaves the a11y maneuver node undescribed, so the arrow can only come from the
 * notification and the cluster shows none. The self-heal must judge the binding, not the approval,
 * and must leave a listener that connects on its own alone.
 */
class NotificationListenerBindingIssue325Test {

    private data class Proc(val start: String, val connectDelayMs: Long?)

    private fun ms(t: String): Long {
        val (h, m, s) = t.split(':')
        return (h.toLong() * 3600 + m.toLong() * 60) * 1000 + (s.toDouble() * 1000).toLong()
    }

    private val procs: List<Proc> = run {
        val out = mutableListOf<Proc>()
        var start: String? = null
        var delay: Long? = null
        fun close() { start?.let { out += Proc(it, delay) }; start = null; delay = null }
        javaClass.getResource("/navdata/issue325-listener-binding.txt")!!.readText().lines().forEach { line ->
            when {
                line.startsWith("# Block") -> close()
                line.contains(" app    start version=") -> { close(); start = line.substringBefore(' ') }
                line.contains("notif-listener state=connected") && start != null && delay == null ->
                    delay = ms(line.substringBefore(' ')) - ms(start!!)
            }
        }
        close()
        out
    }

    private fun decisionAt(p: Proc, ageMs: Long) = NotificationListenerHealth.needsRebind(
        approved = true, // grant_heal reports granted=true in every process of this log
        connected = p.connectDelayMs != null && p.connectDelayMs <= ageMs,
        processAgeMs = ageMs,
    )

    @Test fun `car start whose listener never connected gets a rebind`() {
        val p = procs.single { it.start == "17:45:07.206" }
        assertEquals(null, p.connectDelayMs)
        assertTrue("approved but unbound 20 s after start must be rebound (#325)", decisionAt(p, 20_000))
    }

    @Test fun `a listener that connected is never touched`() {
        val connected = procs.filter { it.connectDelayMs != null }
        assertEquals(2, connected.size)
        connected.forEach { p ->
            assertFalse("process ${p.start} connected after ${p.connectDelayMs} ms", decisionAt(p, 20_000))
        }
    }

    @Test fun `no verdict inside the grace window, a healthy restart binds after about 3 s`() {
        val healthy = procs.single { it.start == "12:25:10.564" }
        assertFalse(decisionAt(healthy, 2_000))
        assertFalse(NotificationListenerHealth.needsRebind(approved = true, connected = false, processAgeMs = 2_000))
    }

    @Test fun `a missing approval stays on the grant path, not a rebind`() {
        assertFalse(NotificationListenerHealth.needsRebind(approved = false, connected = false, processAgeMs = 60_000))
    }
}

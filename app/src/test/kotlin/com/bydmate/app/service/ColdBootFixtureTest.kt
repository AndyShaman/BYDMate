package com.bydmate.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field log #491 (DiLink 5.0, v4.5.0): on a parked car the head unit rebooted itself every
 * 3 h 29 min (reboot,bydcloud), each boot started TrackingService on a dark screen and it held its
 * wake lock until the driver came back at 18:03.
 */
class ColdBootFixtureTest {

    private val lines = javaClass.getResource("/parked-restart/cold-boot-bydcloud-491.txt")!!
        .readText().lines().filter { it.isNotBlank() }

    private fun seconds(line: String): Int {
        val (h, m, s) = line.substringBefore(' ').substringBefore('.').split(':').map { it.toInt() }
        return h * 3600 + m * 60 + s
    }

    /** A boot with no service running and no screen-on before the next boot: the car stayed parked. */
    private val parkedBoots = lines.withIndex()
        .filter { (_, l) -> l.contains("boot-receiver action=BOOT_COMPLETED service_running=false") }
        .filter { (i, _) ->
            lines.drop(i + 1).takeWhile { !it.contains("boot-receiver") }.none { it.contains("SCREEN_ON") }
        }
        .map { it.value }

    @Test fun `the log holds three parked reboots 3 h 29 min apart`() {
        assertEquals(3, parkedBoots.size)
        parkedBoots.zipWithNext { a, b -> assertEquals(12_550.0, (seconds(b) - seconds(a)).toDouble(), 30.0) }
    }

    @Test fun `none of the parked reboots keeps the service`() {
        for (boot in parkedBoots) {
            // Dark screen at the worker: the start is skipped.
            assertFalse(boot, ServiceStartWorker.shouldStart("BOOT_COMPLETED", interactive = false, coldBoot = true))
            // Screen still awake at the worker: the service starts and stops when the screen goes off.
            val guard = ColdBootGuard()
            assertFalse(guard.onStart(coldBoot = true, first = true, interactive = true))
            assertTrue(boot, guard.onScreenOff())
        }
    }
}

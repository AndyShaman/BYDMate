package com.bydmate.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Field log 29.09 (DiLink 5.0, v3.19.2): onTaskRemoved -> quickboot force-stop -> the restart worker
 * ran ~30 s later on the parked car and started TrackingService with the screen off.
 */
class ParkedRestartFixtureTest {

    private val lines = javaClass.getResource("/parked-restart/task-removed-parked-restart.log")!!
        .readText().lines().filter { it.isNotBlank() }

    @Test fun `the parked restart from the field log is skipped`() {
        val trigger = lines.firstNotNullOf { Regex("start-worker trigger=worker:(\\S+)").find(it)?.groupValues?.get(1) }
        assertEquals(AutostartTrace.SOURCE_TASK_REMOVED, trigger)
        val screenOff = lines.any { it.contains("screen off at start") }
        assertFalse(ServiceStartWorker.shouldStart(trigger, interactive = !screenOff))
    }
}

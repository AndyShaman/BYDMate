package com.bydmate.app.service

import com.bydmate.app.diagnostics.TraceSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The autostart trace lines exist to answer, from one user dump, what started the service and
 * whether our own a11y recovery killed the process. Their values must be short code words that
 * survive the trace sanitizer as written.
 */
class AutostartTraceTest {

    @Test fun `a broadcast action is named by its last segment`() {
        assertEquals("BOOT_COMPLETED", AutostartTrace.actionName("android.intent.action.BOOT_COMPLETED"))
        assertEquals("MY_PACKAGE_REPLACED", AutostartTrace.actionName("android.intent.action.MY_PACKAGE_REPLACED"))
        assertEquals("RECOVER_START", AutostartTrace.actionName(BootReceiver.ACTION_RECOVER_START))
    }

    @Test fun `a missing action is unknown`() {
        assertEquals("unknown", AutostartTrace.actionName(null))
        assertEquals("unknown", AutostartTrace.actionName(""))
    }

    @Test fun `a null start intent is the sticky restart`() {
        assertEquals("sticky_restart", AutostartTrace.startTrigger(intentPresent = false, extra = null))
    }

    @Test fun `a start intent without a trigger is unknown`() {
        assertEquals("unknown", AutostartTrace.startTrigger(intentPresent = true, extra = null))
        assertEquals("unknown", AutostartTrace.startTrigger(intentPresent = true, extra = ""))
    }

    @Test fun `a start intent carries its trigger through`() {
        assertEquals("activity", AutostartTrace.startTrigger(intentPresent = true, extra = AutostartTrace.TRIGGER_ACTIVITY))
        assertEquals("worker:BOOT_COMPLETED", AutostartTrace.startTrigger(true, "worker:BOOT_COMPLETED"))
    }

    @Test fun `the worker trigger names who enqueued it`() {
        assertEquals("worker:BOOT_COMPLETED", AutostartTrace.workerTrigger("BOOT_COMPLETED"))
        assertEquals("worker:service_destroyed", AutostartTrace.workerTrigger(AutostartTrace.SOURCE_SERVICE_DESTROYED))
        assertEquals("worker:unknown", AutostartTrace.workerTrigger(null))
    }

    @Test fun `the direct fallback names the broadcast that needed it`() {
        assertEquals("boot_direct:USER_PRESENT", AutostartTrace.directFallbackTrigger("android.intent.action.USER_PRESENT"))
    }

    @Test fun `re-asserts print one digit per try, a dash when none was needed`() {
        assertEquals("-", AutostartTrace.reasserts(emptyList()))
        assertEquals("10", AutostartTrace.reasserts(listOf(true, false)))
    }

    @Test fun `trigger values pass the trace sanitizer unchanged`() {
        listOf(
            AutostartTrace.workerTrigger("MY_PACKAGE_REPLACED"),
            AutostartTrace.directFallbackTrigger("android.intent.action.QUICKBOOT_POWERON"),
            AutostartTrace.startTrigger(false, null),
            AutostartTrace.TRIGGER_A11Y,
            AutostartTrace.TRIGGER_WELCOME,
            AutostartTrace.SOURCE_TASK_REMOVED,
        ).forEach { assertEquals(it, TraceSanitizer.value(it)) }
    }
}

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

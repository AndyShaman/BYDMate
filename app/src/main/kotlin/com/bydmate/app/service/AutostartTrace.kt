package com.bydmate.app.service

/**
 * Values of the autostart and a11y-recovery trace lines, kept out of the Android components so they
 * can be tested. A trigger says which entry point started TrackingService: it travels as the start
 * intent's [EXTRA_TRIGGER], and through WorkManager as the worker's [KEY_WORKER_SOURCE] input.
 */
internal object AutostartTrace {
    const val EXTRA_TRIGGER = "trigger"
    const val KEY_WORKER_SOURCE = "source"

    const val TRIGGER_ACTIVITY = "activity"
    const val TRIGGER_WELCOME = "welcome"
    const val TRIGGER_A11Y = "a11y_connected"

    /** Worker sources besides a broadcast action: the service scheduling its own restart. */
    const val SOURCE_SERVICE_DESTROYED = "service_destroyed"
    const val SOURCE_TASK_REMOVED = "task_removed"

    private const val UNKNOWN = "unknown"

    /** "android.intent.action.BOOT_COMPLETED" -> "BOOT_COMPLETED": the package part adds nothing. */
    fun actionName(action: String?): String = action?.substringAfterLast('.')?.ifEmpty { null } ?: UNKNOWN

    /** START_STICKY brings a killed service back with a null intent; a caller without a trigger is unknown. */
    fun startTrigger(intentPresent: Boolean, extra: String?): String =
        if (!intentPresent) "sticky_restart" else extra?.ifEmpty { null } ?: UNKNOWN

    fun workerTrigger(source: String?): String = "worker:" + (source?.ifEmpty { null } ?: UNKNOWN)

    /** BootReceiver's last resort when WorkManager could not enqueue. */
    fun directFallbackTrigger(action: String?): String = "boot_direct:" + actionName(action)

    /** The daemon's answer per grant re-assert, "10" = ok then failed; "-" when none was needed. */
    fun reasserts(reasserts: List<Boolean>): String =
        reasserts.joinToString("") { if (it) "1" else "0" }.ifEmpty { "-" }
}

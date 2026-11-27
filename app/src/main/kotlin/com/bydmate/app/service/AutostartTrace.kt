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

    /** Firmware extra on BOOT_COMPLETED: true on every car wake (DiLink 5.0 Utils.startAccOn). */
    const val EXTRA_FROM_QUICKBOOT = "from_quickboot"

    /** Worker input and service extra: the start came from a cold boot, see [isColdBoot]. */
    const val KEY_COLD_BOOT = "cold_boot"

    /** Worker source of a start that waited for the screen, see [ColdBootDeferral]. */
    const val SOURCE_SCREEN_ON = "screen_on"

    private const val UNKNOWN = "unknown"
    private const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    private const val ANDROID_12_MIN_SDK = 31
    private const val ANDROID_12_MAX_SDK = 32

    /**
     * A BOOT_COMPLETED without [EXTRA_FROM_QUICKBOOT] is a cold boot: the head unit rebooted itself
     * on a parked car (reboot,bydcloud, every ~3.5 h on some firmwares) or the user restarted it.
     * A car wake, remote start included, comes out of quickboot and carries the flag.
     */
    fun isColdBoot(action: String?, fromQuickboot: Boolean): Boolean =
        action == ACTION_BOOT_COMPLETED && !fromQuickboot

    /** Cold-boot deferral is enabled only on Android 12 (DiLink 5.0), where quickboot extras were verified. */
    fun coldBootGuardApplies(sdk: Int): Boolean = sdk in ANDROID_12_MIN_SDK..ANDROID_12_MAX_SDK

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

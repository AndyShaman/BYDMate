package com.bydmate.app.helper

/**
 * Enables our steering-wheel accessibility service so it starts filtering steering-wheel keys.
 * DiLink has no a11y settings UI, so the user cannot toggle it; we do it under shell uid via the
 * `settings` binary (the in-process Settings.Secure ContentResolver does not stick for an
 * app_process-spawned daemon).
 *
 * Force re-bind, mirroring OpenBYD AccessibilitySetupHelper.buildEnableCommands: write the list
 * WITHOUT our component, pause, then write it back WITH our component, then accessibility_enabled=1.
 * A plain append does NOT make the framework bind a service that is already listed but not running
 * (the common case after an APK reinstall/upgrade) — the remove+re-add is what triggers the bind.
 * Read-modify-write preserves other apps' services (our component is the only one we ever touch).
 * App-scoped, reversible Secure settings only; touches nothing on the vehicle (no autoservice/CAN).
 */
internal fun enableAccessibilityService(): Boolean {
    val component = HelperBinderProtocol.ACCESSIBILITY_SERVICE_COMPONENT
    val target = canonicalComponent(component)
    // Abort on a failed READ — never write a guessed/garbled list back, that would clobber others.
    val current = readSecure("enabled_accessibility_services") ?: return false
    // Strip EVERY spelling of our component. The short ("pkg/.Cls") and full ("pkg/pkg.Cls") forms
    // both canonicalise to the same ComponentName, so a literal-string filter would leave the other
    // form behind. The framework would then see no change to the enabled SET and never re-bind a
    // crashed service — the exact failure that kept star control dead after a reboot.
    val others = current.split(':').filter { it.isNotEmpty() && canonicalComponent(it) != target }
    // $1 = the list, passed as argv (not interpolated) — safe even if an existing entry is odd.
    if (shExec("settings put secure enabled_accessibility_services \"\$1\"", others.joinToString(":")).code != 0) return false
    Thread.sleep(200L)  // let the framework observe the removal before we re-add (OpenBYD uses 0.2s)
    if (shExec("settings put secure enabled_accessibility_services \"\$1\"", (others + component).joinToString(":")).code != 0) return false
    if (shExec("settings put secure accessibility_enabled 1").code != 0) return false
    // Verify read-back: our component is now listed AND accessibility is enabled.
    val after = (readSecure("enabled_accessibility_services") ?: return false).split(':').filter { it.isNotEmpty() }
    return after.any { canonicalComponent(it) == target } && readSecure("accessibility_enabled") == "1"
}

/**
 * Recovers the a11y service on Android 10 after the firmware's quickboot force-stop parked our
 * component in AccessibilityManagerService's UserState.mBindingServices (AOSP Q: no
 * ACTION_PACKAGE_RESTARTED broadcast, so AMS re-binds into the dying package and the bind never
 * completes; updateServicesLocked skips such components forever). A settings rewrite cannot leave
 * that state — only PackageMonitor.onHandleForceStop clears it, which
 * IActivityManager.forceStopPackage triggers. So: force-stop ourselves, re-enable, restart.
 *
 * The app is killed by step 1, so it never sees the reply; the daemon survives (shell uid, own
 * process) and finishes the sequence. Kept dumb and logged — the SDK gate lives in the app.
 */
internal fun recoverAccessibilityService(): Boolean {
    val tag = "bydmate_helper"
    val pkg = HelperBinderProtocol.APP_PACKAGE
    android.util.Log.w(tag, "a11y recover: force-stopping $pkg (AOSP Q stuck binding)")
    try {
        forceStopPackage(pkg)
    } catch (e: Throwable) {
        android.util.Log.w(tag, "a11y recover: force-stop failed: ${e.javaClass.simpleName}: ${e.message}")
        return false
    }
    Thread.sleep(700L)  // let AccessibilityManagerService process onHandleForceStop
    // The app is dead from here on, so the restart goes out FIRST and does not depend on anything
    // else in this function: a hung `settings` must not leave TrackingService stopped until boot.
    // TrackingService is not exported, so shell uid cannot start it directly; BootReceiver is
    // (BOOT_COMPLETED needs it) and its WorkManager chain starts the service. Explicit component +
    // include-stopped-packages: the force-stop just put the package into the stopped state.
    // --receiver-foreground: a plain shell broadcast goes to the background queue, behind other
    // apps' BOOT_COMPLETED receivers after a quick boot; Atto 3 logs showed 11-44 s from the
    // force-stop to BootReceiver, with the widget and key service gone for that long.
    var am = ""
    var restarted = false
    for (attempt in 1..2) {
        am = runCatching {
            amShell(
                "am broadcast --include-stopped-packages --receiver-foreground -a com.bydmate.app.action.RECOVER_START -n \"\$1\"",
                listOf("$pkg/com.bydmate.app.service.BootReceiver"),
            )
        }.getOrElse { "broadcast failed: ${it.javaClass.simpleName}: ${it.message}" }
        restarted = am.contains("Broadcast completed")
        if (restarted) break
        android.util.Log.w(tag, "a11y recover: restart broadcast attempt $attempt failed: ${am.take(200)}")
        Thread.sleep(1000L)
    }
    // Re-assert the a11y setting: onHandleForceStop removed us from the enabled set as well.
    val ok = try {
        enableAccessibilityService()
    } catch (e: Throwable) {
        android.util.Log.w(tag, "a11y recover: re-assert failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
    android.util.Log.i(tag, "a11y recover: reassert=$ok restart=$restarted am=${am.take(200)}")
    logA11yFrameworkStateAsync(ok)  // diagnostics only
    return ok && restarted
}

/**
 * Logs the framework's own accessibility bookkeeping after a re-assert, under the daemon tag the
 * app's log recorder already captures. `dumpsys accessibility` prints the AOSP UserState sets
 * (Bound / Enabled / Binding services) on one line each; `dumpsys activity services` shows whether
 * ActivityManager still holds a ServiceRecord + connection for our key filter. Field evidence for
 * the Android 10 "stuck in mBindingServices" case that a settings rewrite cannot leave.
 * Read-only: two dumpsys calls, output trimmed to a handful of lines.
 */
internal fun logA11yFrameworkStateAsync(reassertOk: Boolean) {
    val t = Thread({ runCatching { logA11yFrameworkState(reassertOk) } }, "bydmate-a11ydiag")
    t.isDaemon = true
    t.start()
}

/** Diagnostics only; every dumpsys is time- and size-bounded so it can never wedge the daemon. */
private fun logA11yFrameworkState(reassertOk: Boolean) {
    val tag = "bydmate_helper"
    val a11y = shExecBounded("dumpsys accessibility").lines()
    val keep = a11y.filter { l ->
        l.contains("User state[") || l.contains("services:{") || l.contains("bydmate", ignoreCase = true)
    }.take(12)
    android.util.Log.i(tag, "a11y state after reassert ok=$reassertOk: sdk=${android.os.Build.VERSION.SDK_INT} lines=${a11y.size}")
    keep.forEach { android.util.Log.i(tag, "a11y: " + it.trim().take(300)) }
    val pid = shExecBounded("pidof com.bydmate.app")
    val stopped = shExecBounded("dumpsys package com.bydmate.app").lines()
        .firstOrNull { it.contains("stopped=", ignoreCase = true) }?.trim()?.take(200)
    android.util.Log.i(tag, "pkg: pid=${pid.ifEmpty { "none" }} $stopped")
    val am = shExecBounded("dumpsys activity services com.bydmate.app").lines()
    val start = am.indexOfFirst { it.contains("SteeringWheelKeyService") }
    if (start < 0) {
        android.util.Log.i(tag, "am: no ServiceRecord for SteeringWheelKeyService (lines=${am.size})")
        return
    }
    am.drop(start).take(30).forEach { android.util.Log.i(tag, "am: " + it.trim().take(300)) }
}

/**
 * Self-grants notification-listener access for our MediaSessionListenerService stub.
 *
 * Primary: `cmd notification allow_listener <component>` — the official NMS API that writes to
 * the canonical approved list inside NotificationManagerService. On some firmwares (Sea Lion 06/07)
 * NMS reconciles enabled_notification_listeners from that internal list on every Settings change,
 * so a raw settings-put entry is stripped immediately; cmd allow_listener is the only path that
 * sticks (donor-verified). Verified by reading back the secure setting.
 *
 * Fallback: remove-wait-readd settings-put cycle — for firmwares that predate `cmd notification`.
 * NMS's Settings observer only re-binds listeners when the string actually changes value, so
 * re-adding a component already present would silently no-op after a crash.
 * Read-modify-write preserves other apps' listeners (our component is the only one we ever touch).
 *
 * App-scoped, reversible Secure setting only; touches nothing on the vehicle (no autoservice/CAN).
 */
internal fun enableNotificationListener(): Boolean {
    val component = HelperBinderProtocol.NOTIFICATION_LISTENER_COMPONENT
    val target = canonicalComponent(component)

    // Primary path: cmd notification allow_listener writes to NMS's canonical approved list.
    // On firmwares where NMS reconciles the raw secure setting from that list, raw settings-put
    // entries are stripped on the next reconciliation cycle; this path is immune to that.
    // Readback is gated on exit code == 0: a stale raw entry in the setting could otherwise
    // false-positive the check when the canonical NMS grant is absent (the exact reconciliation
    // case), causing the function to return true before the fallback ever runs.
    val cmdResult = shExec("cmd notification allow_listener \"\$1\"", component)
    if (cmdResult.code == 0) {
        val afterCmd = (readSecure("enabled_notification_listeners") ?: "").split(':').filter { it.isNotEmpty() }
        if (afterCmd.any { canonicalComponent(it) == target }) return true
    }

    // Fallback: remove-wait-readd settings-put for firmwares without `cmd notification`.
    val current = readSecure("enabled_notification_listeners") ?: return false
    val others = current.split(':').filter { it.isNotEmpty() && canonicalComponent(it) != target }
    if (shExec("settings put secure enabled_notification_listeners \"\$1\"", others.joinToString(":")).code != 0) return false
    Thread.sleep(200L)
    if (shExec("settings put secure enabled_notification_listeners \"\$1\"", (others + component).joinToString(":")).code != 0) return false
    val after = (readSecure("enabled_notification_listeners") ?: return false).split(':').filter { it.isNotEmpty() }
    return after.any { canonicalComponent(it) == target }
}

/**
 * Expands a flattened component string to a canonical `pkg/fully.qualified.Class`, mirroring
 * ComponentName.unflattenFromString's leading-dot rule (`pkg/.A.B` -> `pkg/pkg.A.B`). Lets the short
 * and full spellings of one service compare equal. Returns the input unchanged when it has no '/'.
 */
internal fun canonicalComponent(flattened: String): String {
    val sep = flattened.indexOf('/')
    if (sep < 0) return flattened
    val pkg = flattened.substring(0, sep)
    val cls = flattened.substring(sep + 1)
    val fqcn = if (cls.startsWith(".")) pkg + cls else cls
    return "$pkg/$fqcn"
}

/**
 * Reads a secure setting via the `settings` binary: "" when unset (`settings get` prints "null"),
 * or null on a non-zero exit so callers can abort instead of acting on a bad read.
 */
private fun readSecure(key: String): String? {
    val r = shExec("settings get secure \"\$1\"", key)
    if (r.code != 0) return null
    return if (r.stdout == "null") "" else r.stdout
}

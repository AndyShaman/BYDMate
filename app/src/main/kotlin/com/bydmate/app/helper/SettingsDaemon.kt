package com.bydmate.app.helper

import android.content.Context

/**
 * Enables or disables the Wi-Fi hotspot (TETHERING_WIFI = 0) via reflection.
 *
 * Ported from verified autovoice InterconnectionApiImpl (methods f()/l()). Requires
 * TETHER_PRIVILEGED which the shell uid holds. Needs on-car validation.
 *
 * Primary: android.net.BydTetheringInterface (hidden framework class on DiLink head units).
 * Legacy fallback: ConnectivityManager.startTethering/stopTethering via reflection
 * (deprecated API present on older builds).
 */
internal fun setHotspot(ctx: Context, enable: Boolean): Boolean {
    return try {
        // Primary path: BydTetheringInterface (hidden framework class, not in SDK).
        val bydCls = Class.forName("android.net.BydTetheringInterface")
        val bydInstance = bydCls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
        if (enable) {
            // TetheringManager.TetheringRequest.Builder(0 = TETHERING_WIFI).build()
            val builderCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderCls.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
            val request = builderCls.getMethod("build").invoke(builder)
            val requestCls = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val callbackCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            // Build a no-op Proxy matching the verified autovoice pattern (inner class a in
            // InterconnectionApiImpl.java): logging-only onTetheringStarted/onTetheringFailed.
            // Passing null risks an NPE inside the BYD framework wrapper on the callback dispatch.
            val noOpCallback = java.lang.reflect.Proxy.newProxyInstance(
                callbackCls.classLoader,
                arrayOf(callbackCls)
            ) { _, _, _ -> null }
            // startTethering(TetheringRequest, Executor, StartTetheringCallback).
            // Autovoice passes (Executor) null here, but strict AOSP TetheringManager rejects a
            // null executor; a direct executor satisfies both a strict forwarder and a tolerant
            // BYD wrapper, and only ever runs the no-op callback dispatch.
            val directExecutor = java.util.concurrent.Executor { it.run() }
            bydCls.getMethod("startTethering", requestCls,
                java.util.concurrent.Executor::class.java, callbackCls)
                .invoke(bydInstance, request, directExecutor, noOpCallback)
        } else {
            // stopTethering(int type) — 0 = TETHERING_WIFI
            bydCls.getMethod("stopTethering", Int::class.javaPrimitiveType)
                .invoke(bydInstance, 0)
        }
        true
    } catch (primary: Throwable) {
        System.err.println("WARN: BydTetheringInterface failed, trying CM legacy: ${primary.message}")
        // Legacy fallback: ConnectivityManager.startTethering/stopTethering via reflection.
        try {
            val cm = ctx.getSystemService("connectivity") ?: return false
            if (enable) {
                val callbackCls = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
                // OnStartTetheringCallback is an abstract CLASS (not an interface), so Proxy
                // cannot help. Autovoice used dexmaker to subclass it, which is unavailable here.
                // Stock AOSP ConnectivityManager.startTethering begins with
                // Objects.requireNonNull(callback, "OnStartTetheringCallback cannot be null."),
                // so this call will always throw on stock builds. Kept only as a last-resort
                // attempt for non-AOSP ROM variants that tolerate a null callback; accepted risk.
                cm.javaClass.getDeclaredMethod("startTethering",
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, callbackCls)
                    .invoke(cm, 0, false, null)
            } else {
                val m = cm.javaClass.getDeclaredMethod("stopTethering", Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(cm, 0)
            }
            true
        } catch (legacy: Throwable) {
            System.err.println("WARN: CM hotspot legacy fallback failed: ${legacy.message}")
            false
        }
    }
}

private val GLOBAL_SETTING_KEYS = setOf("sentrymode_enabled_switch", "enable_freeform_support", "auto_time_zone")

/**
 * Settings.Global whitelist for TX_PUT_GLOBAL_SETTING: the sentry-mode master switch, the
 * freeform windowing flag (direct cluster projection; the framework reads it once at boot) and
 * the automatic time zone switch (the user's time zone lock, #379: 0 = network zone ignored).
 * Values are bounded to 0/1. Anything else is rejected before any shell command runs.
 */
internal fun globalSettingAllowed(key: String, value: Int): Boolean =
    key in GLOBAL_SETTING_KEYS && value in 0..1

/** Keys [HelperBinderProtocol.TX_GET_GLOBAL_SETTING] may read back: the write whitelist's keys. */
internal fun globalSettingReadable(key: String): Boolean =
    key in GLOBAL_SETTING_KEYS

/** How long the read below waits for `settings get global` before it gives up on it. */
internal const val GLOBAL_SETTING_READ_TIMEOUT_MS = 2000L

/** A settings value is a handful of bytes; anything longer is not one and is not worth buffering. */
private const val GLOBAL_SETTING_READ_MAX_BYTES = 256

/**
 * Read side of [globalSettingAllowed]: the same narrow key set, so this stays a sentry/freeform
 * channel and not a generic settings passthrough. The shell call is bounded — this runs on the
 * binder thread the app blocks on, and a `settings` that never returns would pin it (and the
 * helper mutex behind it) forever. Not whitelisted, not a number, or the bound expired -> null,
 * and the caller refuses the toggle instead of guessing a state.
 */
internal fun readGlobalSetting(key: String): Int? {
    if (!globalSettingReadable(key)) return null
    val out = runCatching {
        shExecBounded("settings get global \"\$1\"", GLOBAL_SETTING_READ_TIMEOUT_MS, GLOBAL_SETTING_READ_MAX_BYTES, key)
    }.getOrElse { return null }
    return parseGlobalSettingValue(out)
}

/** First line of `settings get global` as an Int; "null", noise or a `[timeout …]` marker -> null. */
internal fun parseGlobalSettingValue(output: String): Int? =
    output.lineSequence().firstOrNull()?.trim()?.toIntOrNull()

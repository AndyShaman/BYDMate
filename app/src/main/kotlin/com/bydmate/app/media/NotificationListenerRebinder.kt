package com.bydmate.app.media

import android.util.Log

/**
 * #325: on some DiLink 4 firmwares (Han 2025-06, Seal 2024-01) the listener stays approved after a
 * car start but NotificationManagerService never binds it, so no navigator notification reaches
 * us. The grant self-heal sees the approval and reports healthy; this judges the binding.
 */
object NotificationListenerHealth {
    /** Healthy starts bind within ~10 s (2.6-5.4 s typical); 20 s leaves twice that margin. */
    const val GRACE_MS = 20_000L

    /** True only for an approved listener still unbound [GRACE_MS] into the process. A missing
     *  approval belongs to the grant path, never to a rebind. */
    fun needsRebind(approved: Boolean, connected: Boolean, processAgeMs: Long): Boolean =
        approved && !connected && processAgeMs >= GRACE_MS
}

/** Someone holding a registration the rebind drops silently (ClusterMusicBridge's sessions listener). */
interface RebindHook {
    fun beforeRebind()
    fun afterRebind()
}

/**
 * Rebinds the listener the way OpenBYD does: disallow, a short gap, allow. Both go through the
 * helper daemon, which only touches NotificationManagerService; the component is never toggled
 * through PackageManager (that broadcast reaches the fragile Android 10 a11y binding too).
 * At most [MAX_ATTEMPTS] per process. [rebind] blocks (daemon calls and [sleep]): call it off the
 * main thread.
 */
class NotificationListenerRebinder(
    private val disallow: () -> Boolean,
    private val allow: () -> Boolean,
    private val sleep: (Long) -> Unit,
    private val hooks: List<RebindHook>,
) {
    @Volatile
    var attempts: Int = 0
        private set

    @Synchronized
    fun rebind(reason: String): Boolean {
        if (attempts >= MAX_ATTEMPTS) {
            Log.i(TAG, "rebind skipped reason=$reason attempts=$attempts max=$MAX_ATTEMPTS")
            return false
        }
        attempts++
        hooks.forEach { h -> runCatching { h.beforeRebind() }.onFailure { Log.w(TAG, "beforeRebind failed: $it") } }
        val ok = try {
            val disallowed = disallow()
            Log.i(TAG, "rebind reason=$reason attempt=$attempts disallow=$disallowed")
            // An old daemon without the op, or a refused one: nothing was revoked, nothing to give back.
            disallowed && allowWithRetry(reason)
        } finally {
            hooks.forEach { h -> runCatching { h.afterRebind() }.onFailure { Log.w(TAG, "afterRebind failed: $it") } }
        }
        Log.i(TAG, "rebind reason=$reason attempt=$attempts ok=$ok")
        return ok
    }

    // Never leave the listener revoked if a second try can help; after that the grant self-heal re-allows.
    private fun allowWithRetry(reason: String): Boolean {
        repeat(ALLOW_TRIES) { i ->
            sleep(GAP_MS)
            val allowed = allow()
            Log.i(TAG, "rebind reason=$reason allow try=${i + 1} ok=$allowed")
            if (allowed) return true
        }
        return false
    }

    companion object {
        private const val TAG = "NotifListenerRebind"
        const val MAX_ATTEMPTS = 2
        const val GAP_MS = 200L
        private const val ALLOW_TRIES = 2
    }
}

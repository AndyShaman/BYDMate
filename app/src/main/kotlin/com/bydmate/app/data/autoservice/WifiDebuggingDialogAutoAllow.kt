package com.bydmate.app.data.autoservice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Confirms the system "Allow wireless debugging on this network?" dialog for the ADB restore.
 *
 * Android trusts a network by BSSID only, and a phone hotspot gets a new one on every start, so
 * "Always allow on this network" never sticks there and the dialog comes back after each reboot.
 * The click that confirms it needs MANAGE_DEBUGGING, which neither the app nor the (not yet
 * running) daemon can use — our own accessibility service is the only thing that can press it.
 */
object WifiDebuggingDialogAutoAllow {
    private const val TAG = "AdbRestore"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val DIALOG_CLASS_SUFFIX = "WifiDebuggingActivity"
    private const val ALWAYS_USE_ID = "com.android.internal:id/alwaysUse"
    private const val ALLOW_BUTTON_ID = "android:id/button1"

    sealed class Outcome {
        data class Allowed(val checkboxTicked: Boolean) : Outcome() {
            override fun toString() = "Allowed(checkbox=$checkboxTicked)"
        }
        object NoAllowButton : Outcome() {
            override fun toString() = "NoAllowButton"
        }
    }

    /** SharedPreferences key (in [AdbRestorePreferencesImpl.PREFS_NAME]) of the last outcome, for the dump. */
    const val KEY_LAST_OUTCOME = "a11y_auto_allow_last"

    // The dialog window can appear before its content is laid out; one late look catches that.
    private const val RETRY_DELAY_MS = 300L

    // One pending retry at a time: a burst of window events must not stack up delayed clicks.
    private val retryPending = AtomicBoolean(false)

    fun isDialog(pkg: CharSequence?, cls: CharSequence?): Boolean =
        pkg?.toString() == SYSTEMUI_PACKAGE && cls?.toString()?.endsWith(DIALOG_CLASS_SUFFIX) == true

    /**
     * Entry point from the service for a window-state event already matched by [isDialog]. With
     * the toggle off the user did not ask us to manage ADB, so the dialog is left to them.
     * [postDelayed] is a seam for tests; the service runs on the main thread, so does the retry.
     */
    fun onDialogShown(
        service: AccessibilityService,
        appContext: Context,
        event: AccessibilityEvent,
        restoreEnabled: Boolean,
        postDelayed: (Long, () -> Unit) -> Unit = ::postOnMain,
    ) {
        if (!restoreEnabled) {
            record(appContext, "skipped: toggle off")
            return
        }
        // Only the id is kept: the framework recycles the event object after this call.
        val windowId = event.windowId
        val fromSource = rootFromSource(event, windowId)
        val via = if (fromSource != null) "source" else "window"
        val root = fromSource ?: rootOfWindow(service, windowId)
        val outcome = root?.let { allow(it) } ?: Outcome.NoAllowButton
        record(appContext, "$outcome via=$via")
        if (outcome is Outcome.Allowed || !retryPending.compareAndSet(false, true)) return
        postDelayed(RETRY_DELAY_MS) {
            retryPending.set(false)
            val retried = rootOfWindow(service, windowId)?.let { allow(it) } ?: Outcome.NoAllowButton
            record(appContext, if (retried is Outcome.Allowed) "$retried via=retry" else "$retried (retry)")
        }
    }

    /**
     * Ticks "Always allow" when it is there and unticked (useless on a random-BSSID hotspot, saves
     * the next dialog on a router), then presses Allow. Nodes can go stale mid-call, so every
     * accessibility call is guarded and a failure only degrades the outcome.
     */
    fun allow(root: AccessibilityNodeInfo): Outcome {
        val checkbox = runCatching { root.findAccessibilityNodeInfosByViewId(ALWAYS_USE_ID)?.firstOrNull() }
            .getOrNull()
        var ticked = false
        if (checkbox != null) {
            ticked = runCatching { checkbox.isChecked }.getOrDefault(false)
            if (!ticked) {
                ticked = runCatching { checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .onFailure { Log.w(TAG, "wireless debugging dialog: checkbox click failed: ${it.message}") }
                    .getOrDefault(false)
            }
        }
        val button = runCatching { root.findAccessibilityNodeInfosByViewId(ALLOW_BUTTON_ID)?.firstOrNull() }
            .getOrNull() ?: return Outcome.NoAllowButton
        runCatching { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .onFailure { Log.w(TAG, "wireless debugging dialog: allow click failed: ${it.message}") }
        return Outcome.Allowed(ticked)
    }

    /**
     * The dialog's own root, reached by walking up from the event source. rootInActiveWindow is
     * not used: with the cluster projection it can point at another display. The root must belong
     * to the event's window when that is readable, so a click can only land inside the dialog.
     */
    private fun rootFromSource(event: AccessibilityEvent, windowId: Int): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo = runCatching { event.source }.getOrNull() ?: return null
        while (true) node = runCatching { node.parent }.getOrNull() ?: break
        val rootWindow = runCatching { node.window?.id }.getOrNull()
        return node.takeIf { rootWindow == null || rootWindow == windowId }
    }

    /** Root of the window the event came from; any other SystemUI window is never touched. */
    private fun rootOfWindow(service: AccessibilityService, windowId: Int): AccessibilityNodeInfo? =
        runCatching { service.windows.firstOrNull { it.id == windowId }?.root }.getOrNull()

    private fun postOnMain(delayMs: Long, block: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed(block, delayMs)
    }

    private fun record(context: Context, outcome: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching {
            context.getSharedPreferences(AdbRestorePreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_OUTCOME, "$stamp $outcome").apply()
        }
        Log.i(TAG, "wireless debugging dialog: $outcome")
    }
}

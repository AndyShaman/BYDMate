package com.bydmate.app.data.autoservice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
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
 *
 * Android 12 SystemUI shows the dialog as a plain `android.app.Dialog`, the same class as the
 * "Allow USB debugging?" dialog — pressing Allow on that one would let a foreign computer in. So
 * a plain Dialog is only pressed when the window names the BSSID we are connected to and our own
 * `adb_wifi_enabled` write is recent; whenever either is in doubt, it is left to the user.
 */
object WifiDebuggingDialogAutoAllow {
    private const val TAG = "AdbRestore"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val DIALOG_CLASS_SUFFIX = "WifiDebuggingActivity"
    private const val OLD_DIALOG_CLASS = "com.android.systemui.wifi.WifiDebuggingActivity"
    private const val PLAIN_DIALOG_CLASS = "android.app.Dialog"
    // Framework resource id first (Android 12 layout), the older internal spelling second.
    private val ALWAYS_USE_IDS = listOf("android:id/alwaysUse", "com.android.internal:id/alwaysUse")
    private const val ALLOW_BUTTON_ID = "android:id/button1"
    private const val MAC_PAIRS = 6
    // Every maximal run of colon-separated hex pairs: a MAC is six pairs, an MD5 fingerprint sixteen.
    private val HEX_CHAIN = Regex("(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2})+(?![0-9A-Fa-f:])")

    // What WifiManager returns when the BSSID is hidden from us: as good as no BSSID at all.
    private const val MASKED_BSSID = "02:00:00:00:00:00"

    sealed class Outcome {
        data class Allowed(val checkboxTicked: Boolean) : Outcome() {
            override fun toString() = "Allowed(checkbox=$checkboxTicked)"
        }
        object NoAllowButton : Outcome() {
            override fun toString() = "NoAllowButton"
        }
    }

    /** What [decide] says about one dialog window. */
    sealed class Decision {
        /** Not a window we ever touch. */
        data object NotCandidate : Decision()
        data object Press : Decision()
        /** A candidate whose Allow button is not (yet) there. */
        data object NoAllowButton : Decision()
        /** A candidate left to the user; [reason] goes to the dump and the journal. */
        data class Skip(val reason: String) : Decision()
    }

    /** SharedPreferences key (in [AdbRestorePreferencesImpl.PREFS_NAME]) of the last outcome, for the dump. */
    const val KEY_LAST_OUTCOME = "a11y_auto_allow_last"

    // The dialog window can appear before its content is laid out; one late look catches that.
    private const val RETRY_DELAY_MS = 300L

    // One pending retry at a time: a burst of window events must not stack up delayed clicks.
    private val retryPending = AtomicBoolean(false)

    /** Cheap prefilter for the service: SystemUI windows that may be the dialog. [decide] has the final word. */
    fun isDialog(pkg: CharSequence?, cls: CharSequence?): Boolean {
        if (pkg?.toString() != SYSTEMUI_PACKAGE) return false
        val name = cls?.toString() ?: return false
        return name.endsWith(DIALOG_CLASS_SUFFIX) || name == PLAIN_DIALOG_CLASS
    }

    /**
     * May Allow be pressed in this window? The old WifiDebuggingActivity class names the dialog by
     * itself and keeps the rule it always had (toggle on, button there). A plain Dialog also needs
     * a recent enable-write of ours (nothing of ours raised a dialog otherwise) and the current
     * BSSID in its text (the USB debugging dialog shows a key fingerprint instead).
     */
    @Suppress("LongParameterList")
    fun decide(
        pkg: CharSequence?,
        cls: CharSequence?,
        hasAllowButton: Boolean,
        windowText: CharSequence?,
        currentBssid: String?,
        restoreEnabled: Boolean,
        recentWrite: Boolean,
    ): Decision {
        if (!isDialog(pkg, cls)) return Decision.NotCandidate
        if (!restoreEnabled) return Decision.Skip("toggle off")
        if (!hasAllowButton) return Decision.NoAllowButton
        if (cls?.toString() != PLAIN_DIALOG_CLASS) return Decision.Press
        if (!recentWrite) return Decision.Skip("no recent adb_wifi_enabled write of ours")
        val bssid = currentBssid?.trim()
        if (bssid.isNullOrEmpty() || bssid.equals(MASKED_BSSID, ignoreCase = true)) {
            // Android hides the BSSID from apps while location is off. The two dialogs still differ
            // by their text: wireless debugging names one network address of six pairs, USB
            // debugging a key fingerprint, which is a longer chain.
            return if (hasLoneNetworkAddress(windowText)) Decision.Press
            else Decision.Skip("bssid unknown, no lone network address in window")
        }
        if (windowText?.contains(bssid, ignoreCase = true) != true) return Decision.Skip("bssid not in window")
        // The same six pairs can sit inside a key fingerprint: the text check holds here too.
        return if (hasLoneNetworkAddress(windowText)) Decision.Press
        else Decision.Skip("bssid in window, but no lone network address")
    }

    /** True when [text] holds exactly one six-pair address and no longer colon-separated hex chain (a key fingerprint). */
    private fun hasLoneNetworkAddress(text: CharSequence?): Boolean {
        if (text == null) return false
        val chains = HEX_CHAIN.findAll(text).map { it.value.count { c -> c == ':' } + 1 }.toList()
        return chains.count { it == MAC_PAIRS } == 1 && chains.none { it > MAC_PAIRS }
    }

    /**
     * Entry point from the service for a window-state event already matched by [isDialog]; [pkg]
     * and [cls] are the event's. With the toggle off the user did not ask us to manage ADB, so the
     * dialog is left to them. [postDelayed] is a seam for tests; the service runs on the main
     * thread, so does the retry.
     */
    @Suppress("LongParameterList")
    fun onDialogShown(
        service: AccessibilityService,
        appContext: Context,
        event: AccessibilityEvent,
        restoreEnabled: Boolean,
        postDelayed: (Long, () -> Unit) -> Unit = ::postOnMain,
        pkg: CharSequence? = SYSTEMUI_PACKAGE,
        cls: CharSequence? = OLD_DIALOG_CLASS,
    ) {
        if (!restoreEnabled) {
            handle(appContext, pkg, cls, null, restoreEnabled = false, via = "event")
            return
        }
        // Only the id is kept: the framework recycles the event object after this call.
        val windowId = event.windowId
        val fromSource = rootFromSource(event, windowId)
        val via = if (fromSource != null) "source" else "window"
        val root = fromSource ?: rootOfWindow(service, windowId)
        val decision = handle(appContext, pkg, cls, root, restoreEnabled = true, via = via)
        if (decision !== Decision.NoAllowButton || !retryPending.compareAndSet(false, true)) return
        postDelayed(RETRY_DELAY_MS) {
            retryPending.set(false)
            handle(appContext, pkg, cls, rootOfWindow(service, windowId), restoreEnabled = true, via = "retry")
        }
    }

    /**
     * The dialog can be on screen before the framework binds the service (it binds at boot, and
     * again after our own enable), and then no event for it ever reaches us. One look at the open
     * windows once the service is connected; the window class is unknown there, so every SystemUI
     * window with an Allow button goes through the strict plain-Dialog check.
     */
    fun onServiceConnected(
        service: AccessibilityService,
        appContext: Context,
        postDelayed: (Long, () -> Unit) -> Unit = ::postOnMain,
    ) {
        // Late enough for the window flags set in onServiceConnected to take effect.
        postDelayed(RETRY_DELAY_MS) {
            if (!AdbRestorePreferencesImpl(appContext).isEnabled()) return@postDelayed
            val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            roots.filter { root ->
                runCatching { root.packageName?.toString() }.getOrNull() == SYSTEMUI_PACKAGE &&
                    findFirst(root, ALLOW_BUTTON_ID) != null
            }.forEach { handle(appContext, SYSTEMUI_PACKAGE, PLAIN_DIALOG_CLASS, it, restoreEnabled = true, via = "connect") }
        }
    }

    /**
     * Runs [decide] on [root], presses Allow when it says so and records the result. The facts the
     * plain-Dialog check needs are read only as far as the check gets: a dialog that fails on the
     * write record never has its text read.
     */
    @Suppress("LongParameterList")
    private fun handle(
        appContext: Context,
        pkg: CharSequence?,
        cls: CharSequence?,
        root: AccessibilityNodeInfo?,
        restoreEnabled: Boolean,
        via: String,
    ): Decision {
        val hasButton = root != null && findFirst(root, ALLOW_BUTTON_ID) != null
        val strict = restoreEnabled && hasButton && cls?.toString() == PLAIN_DIALOG_CLASS
        val recentWrite = strict && recentWriteOfOurs(appContext)
        val bssid = if (recentWrite) currentBssid(appContext) else null
        val text = if (recentWrite) windowText(root) else null
        val decision = decide(pkg, cls, hasButton, text, bssid, restoreEnabled, recentWrite)
        val outcome = if (decision === Decision.Press) root?.let { allow(it) } ?: Outcome.NoAllowButton else null
        report(appContext, "pkg=$pkg cls=$cls", decision, outcome, via)
        return decision
    }

    /** One line in the last-outcome record and one in the journal per handled window. */
    private fun report(appContext: Context, window: String, decision: Decision, outcome: Outcome?, via: String) {
        val (label, reason) = when (decision) {
            is Decision.Skip -> "skip" to decision.reason
            Decision.Press -> "press" to outcome.toString()
            Decision.NoAllowButton -> "no-button" to null
            Decision.NotCandidate -> return
        }
        val line = when {
            decision is Decision.Skip -> "skipped: $reason $window" + if (via == "retry") " (retry)" else ""
            via == "retry" && outcome !is Outcome.Allowed -> "$window ${outcome ?: Outcome.NoAllowButton} (retry)"
            else -> "$window ${outcome ?: Outcome.NoAllowButton} via=$via"
        }
        record(appContext, line)
        // Window identity, decision and reason only: never the SSID, BSSID or any other window text.
        Trace.event(TraceArea.APP, "adb-dialog", "window" to window, "decision" to label,
            "reason" to reason, "via" to via)
    }

    /**
     * Ticks "Always allow" when it is there and unticked (useless on a random-BSSID hotspot, saves
     * the next dialog on a router), then presses Allow. Nodes can go stale mid-call, so every
     * accessibility call is guarded and a failure only degrades the outcome.
     */
    fun allow(root: AccessibilityNodeInfo): Outcome {
        val checkbox = ALWAYS_USE_IDS.firstNotNullOfOrNull { findFirst(root, it) }
        var ticked = false
        if (checkbox != null) {
            ticked = runCatching { checkbox.isChecked }.getOrDefault(false)
            if (!ticked) {
                ticked = runCatching { checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .onFailure { Log.w(TAG, "wireless debugging dialog: checkbox click failed: ${it.message}") }
                    .getOrDefault(false)
            }
        }
        val button = findFirst(root, ALLOW_BUTTON_ID) ?: return Outcome.NoAllowButton
        runCatching { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .onFailure { Log.w(TAG, "wireless debugging dialog: allow click failed: ${it.message}") }
        return Outcome.Allowed(ticked)
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

// Window access and platform reads, kept at file level to keep the object to its decisions.

// A dialog is a handful of nodes; the bound only keeps a strange window from costing much.
private const val MAX_TEXT_NODES = 200

private fun findFirst(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
    runCatching { root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() }.getOrNull()

/** Text and content descriptions of the window, in tree order; only matched, never stored. */
private fun windowText(root: AccessibilityNodeInfo): String {
    val parts = mutableListOf<CharSequence>()
    val queue = ArrayDeque(listOf(root))
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_TEXT_NODES) {
        val node = queue.removeFirst()
        visited++
        runCatching { node.text }.getOrNull()?.let { parts += it }
        runCatching { node.contentDescription }.getOrNull()?.let { parts += it }
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) runCatching { node.getChild(i) }.getOrNull()?.let { queue.addLast(it) }
    }
    return parts.joinToString(" ")
}

/**
 * Our own enable-write raised the dialog only if it is recent. Its record is persisted before
 * the write, and writes on one network are spaced by [AdbRestoreManager.WRITE_COOLDOWN_MS]
 * while the dialog they raise stays up until answered — so the cooldown is the window.
 */
private fun recentWriteOfOurs(appContext: Context): Boolean {
    val at = runCatching { AdbRestorePreferencesImpl(appContext).lastWriteAtMs() }.getOrDefault(0L)
    val age = System.currentTimeMillis() - at
    return at > 0 && age >= 0 && age < AdbRestoreManager.WRITE_COOLDOWN_MS
}

@Suppress("DEPRECATION")
private fun currentBssid(appContext: Context): String? = runCatching {
    (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.bssid
}.getOrNull()

/**
 * The root the event comes from, reached by walking up from its source. rootInActiveWindow is
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

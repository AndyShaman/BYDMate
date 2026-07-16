package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bydmate.app.navdata.NavA11yFeed
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Steering-wheel key filter for cluster projection. When the settings master switch
 * ([ClusterProjectionManager.KEY_MIRROR_ENABLED]) is ON, a short press of the configured trigger
 * button (keycode in [ClusterProjectionManager.KEY_TRIGGER_KEYCODE], default
 * [DEFAULT_TRIGGER_KEYCODE] = the right star) toggles Yandex Navi between the cluster and the centre
 * screen and is consumed; every other key (switch OFF, a non-trigger button, the right-star
 * long-press that opens the native action menu) passes through untouched.
 *
 * Inert unless enabled in secure settings (self-enabled via the daemon when the switch turns on —
 * no Accessibility UI on DiLink) AND the settings switch is on, so it does nothing for users who
 * never opt in.
 */
class SteeringWheelKeyService : AccessibilityService() {

    private var cachedEntryPoint: ClusterEntryPoint? = null
    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS  // 32
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        // Windows-aware reads: the Navigator projected to the instrument cluster lives on
        // display 2 and is invisible to rootInActiveWindow; these flags open getWindows()/
        // getWindowsOnAllDisplays(). Not-important views: some guidance widgets are marked
        // not-important-for-accessibility on the 2026 Navigator build (Codex fix 5).
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
        instance = this
        isConnected = true
        Log.d(TAG, "connected; filtering steering-wheel keys")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (learnMode) {
            return when (learnDecision(event.keyCode, isDown)) {
                LearnAction.CAPTURE -> {
                    capturedKey.value = CaptureResult(event.keyCode, assignable = true)
                    learnMode = false  // got it; dialog moves to the confirm step
                    true
                }
                LearnAction.REJECT -> {
                    capturedKey.value = CaptureResult(event.keyCode, assignable = false)
                    true  // stay in learn mode; dialog shows "can't assign", waits for another key
                }
                LearnAction.CONSUME -> true
            }
        }
        // Voice check: runs after learn-mode, before star decision. Returns true only when voice is
        // enabled and the configured voice key is pressed (isDown). Non-voice keys fall through.
        val voicePrefs = applicationContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val voiceEnabled = voicePrefs.getBoolean("voice_enabled", false)
        val voiceKey = voicePrefs.getInt("voice_keycode", DEFAULT_VOICE_KEYCODE)
        when (voiceDecision(event.keyCode, isDown, voiceEnabled, voiceKey)) {
            VoiceKeyDecision.TRIGGER -> {
                entryPoint().voiceController().onPttPressed()
                return true
            }
            // Swallow the matching key's UP edge too — otherwise it falls through to the
            // native BYD assistant, which owns the same hardware keycode (Finding 2).
            VoiceKeyDecision.CONSUME -> return true
            VoiceKeyDecision.IGNORE -> {}
        }
        val enabled = prefs.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false)
        val trigger = prefs.getInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE)
        return when (starDecision(event.keyCode, isDown, enabled, trigger)) {
            StarDecision.CONSUME_AND_TOGGLE -> {
                val ep = entryPoint()
                ClusterProjectionManager.toggle(applicationContext, ep.helperClient(), ep.helperBootstrap())
                true
            }
            StarDecision.CONSUME -> true
            StarDecision.PASS_THROUGH -> false
        }
    }

    private fun entryPoint(): ClusterEntryPoint =
        cachedEntryPoint ?: EntryPointAccessors
            .fromApplication(applicationContext, ClusterEntryPoint::class.java)
            .also { cachedEntryPoint = it }

    /** Root of the Navigator window wherever it lives: the active window, a minimized
     *  mini-window, or the instrument cluster (display 2, projection mode). Caller must
     *  recycle the returned node. Null when the Navigator has no window anywhere. */
    fun findNavigatorRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            if (active.packageName?.toString() in com.bydmate.app.navdata.NavPackages.YANDEX_NAVI) return active
            @Suppress("DEPRECATION") runCatching { active.recycle() }
        }
        val windowList = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val byDisplay = windowsOnAllDisplays
                (0 until byDisplay.size()).flatMap { byDisplay.valueAt(it) }
            } else windows
        }.getOrNull() ?: return null
        for (window in windowList) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (root.packageName?.toString() in com.bydmate.app.navdata.NavPackages.YANDEX_NAVI) return root
            @Suppress("DEPRECATION") runCatching { root.recycle() }
        }
        return null
    }

    // Single volatile read when the HUD feature is off - see NavA11yFeed.enabled.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        NavA11yFeed.onEvent(this, event)
    }
    override fun onInterrupt() { /* no-op */ }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        isConnected = false
        Log.d(TAG, "unbound; star key filter inactive")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        isConnected = false
        super.onDestroy()
    }

    companion object {
        const val TAG = "SteeringWheelKeySvc"

        /**
         * True liveness signal. Set when the framework actually binds + connects this service (the
         * only moment the key filter is live), cleared on unbind/destroy. The Secure-settings
         * enabled-list is NOT reliable across boot/wake, so the re-assert watchdog in TrackingService
         * gates on THIS first. Mirrors OpenBYD's SteeringWheelAccessibilityService.isConnected.
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        /** Live service instance for on-demand window reads (NaviScreenReader). Set on
         *  connect, cleared on unbind/destroy - callers must handle null. */
        @Volatile
        var instance: SteeringWheelKeyService? = null
            private set

        /**
         * Learn mode: when true, the next steering-wheel key is captured into [capturedKey] instead
         * of toggling projection, and every key is consumed so its native action can't fire. Set by
         * the settings learn dialog; cleared by the service on CAPTURE or by the dialog on dismiss.
         */
        @Volatile
        var learnMode: Boolean = false

        /** Result of a learn-mode key press; the settings dialog (same process) collects this. */
        data class CaptureResult(val keyCode: Int, val assignable: Boolean)

        /** Last captured key while learning; null = nothing captured yet. */
        val capturedKey = MutableStateFlow<CaptureResult?>(null)
    }
}

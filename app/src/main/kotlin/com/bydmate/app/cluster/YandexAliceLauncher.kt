package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.voice.VoiceController
import java.util.ArrayDeque
import java.util.Locale

internal class YandexAliceLauncher(
    private val service: AccessibilityService,
    private val voiceController: () -> VoiceController,
) {
    private val handler = Handler(Looper.getMainLooper())

    private var pending = false
    private var generation = 0
    private var attempt = 0
    private var coldClicks = 0
    private var lastColdClick = 0L
    private var exactClicks = 0
    private var lastExactClick = 0L
    private var lastUiClick = 0L
    private var lastTrigger = 0L
    private var listening = false
    private var suppressNativeUntil = 0L

    fun trigger() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < TRIGGER_DEBOUNCE_MS) return
        lastTrigger = now
        suppressNativeUntil = now + NATIVE_SUPPRESS_MS

        resetAttempt()
        voiceController().beginExternalAssistantAudio()

        pending = true
        if (advance()) return
        pending = false

        val launch = service.packageManager.getLaunchIntentForPackage(YANDEX_PACKAGE)
            ?: return finish()
        val started = runCatching {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            service.startActivity(launch)
        }.isSuccess
        if (!started) return finish()

        pending = true
        generation++
        val current = generation
        handler.postDelayed({ retry(current) }, WARMUP_MS)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString().orEmpty()

        if (
            SystemClock.elapsedRealtime() < suppressNativeUntil &&
            android.os.Build.VERSION.SDK_INT <= 29 &&
            pkg == BYD_VOICE_PACKAGE &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        if (pending && pkg == YANDEX_PACKAGE) advance()

        if (
            listening &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg.isNotBlank() &&
            pkg != YANDEX_PACKAGE
        ) {
            handler.postDelayed({
                val active = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
                if (listening && active != YANDEX_PACKAGE) finish()
            }, 1200L)
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        finish()
    }

    private fun retry(expectedGeneration: Int) {
        if (!pending || expectedGeneration != generation) return
        attempt++
        if (advance()) return
        if (attempt >= MAX_ATTEMPTS) return finish()
        handler.postDelayed({ retry(expectedGeneration) }, RETRY_MS)
    }

    private fun advance(): Boolean {
        val roots = yandexRoots()
        if (roots.isEmpty()) return false

        for (root in roots) {
            val exact = runCatching {
                root.findAccessibilityNodeInfosByViewId(EXACT_VIEW_ID)
            }.getOrNull().orEmpty()
            for (node in exact) {
                val now = SystemClock.elapsedRealtime()
                val coldChain = coldClicks > 0
                if (coldChain && exactClicks > 0 && now - lastExactClick < UI_DEBOUNCE_MS) continue
                val terminal = !coldChain || exactClicks > 0
                if (click(node, terminal)) {
                    if (coldChain) {
                        exactClicks++
                        lastExactClick = now
                    }
                    if (terminal) listening = true
                    return terminal
                }
            }
        }

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited++ < NODE_SCAN_LIMIT) {
                val node = queue.removeFirst()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (desc == "голосовой помощник" || desc == "voice assistant") {
                    if (click(node, terminal = true)) {
                        listening = true
                        return true
                    }
                }
                for (index in 0 until node.childCount) {
                    runCatching { node.getChild(index) }.getOrNull()?.let(queue::addLast)
                }
            }
        }

        if (coldClicks < 3) {
            for (root in roots) {
                for (id in COLD_VIEW_IDS) {
                    val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }
                        .getOrNull().orEmpty()
                    for (node in nodes) {
                        val now = SystemClock.elapsedRealtime()
                        if (coldClicks > 0 && now - lastColdClick < COLD_RETRY_MS) continue
                        if (click(node, terminal = false)) {
                            coldClicks++
                            lastColdClick = now
                            return false
                        }
                    }
                }
            }
        }

        if (coldClicks == 0) {
            for (root in roots) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited++ < NODE_SCAN_LIMIT) {
                    val node = queue.removeFirst()
                    val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                    val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    val cold = id.contains("bro_omnibox_button_microphone") ||
                        desc in COLD_DESCRIPTIONS
                    if (cold && click(node, terminal = false)) {
                        coldClicks++
                        lastColdClick = SystemClock.elapsedRealtime()
                        return false
                    }
                    for (index in 0 until node.childCount) {
                        runCatching { node.getChild(index) }.getOrNull()?.let(queue::addLast)
                    }
                }
            }
        }

        return false
    }

    private fun click(node: AccessibilityNodeInfo, terminal: Boolean): Boolean {
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !target.isClickable && hops++ < 4) {
            target = runCatching { target.parent }.getOrNull()
        }
        if (target?.isClickable != true) return false

        val now = SystemClock.elapsedRealtime()
        if (now - lastUiClick < UI_DEBOUNCE_MS) return false

        val clicked = runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (!clicked) return false

        lastUiClick = now
        if (terminal) {
            pending = false
            generation++
        }
        return true
    }

    private fun yandexRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { service.rootInActiveWindow }.getOrNull()?.let {
            if (it.packageName?.toString() == YANDEX_PACKAGE) roots += it
        }
        runCatching { service.windows }.getOrNull().orEmpty().forEach { window ->
            runCatching { window.root }.getOrNull()?.let {
                if (it.packageName?.toString() == YANDEX_PACKAGE) roots += it
            }
        }
        return roots
    }

    private fun resetAttempt() {
        handler.removeCallbacksAndMessages(null)
        pending = false
        attempt = 0
        coldClicks = 0
        lastColdClick = 0L
        exactClicks = 0
        lastExactClick = 0L
        listening = false
        generation++
    }

    private fun finish() {
        pending = false
        listening = false
        generation++
        voiceController().endExternalAssistantAudio()
    }

    companion object {
        private const val YANDEX_PACKAGE = "com.yandex.browser"
        private const val BYD_VOICE_PACKAGE = "com.byd.vrassistant"
        private const val EXACT_VIEW_ID = "com.yandex.browser:id/alice_input_quarknyx"
        private val COLD_VIEW_IDS = listOf(
            "com.yandex.browser:id/bro_omnibox_button_mic",
            "com.yandex.browser:id/bro_omnibox_button_microphone_inactive",
        )
        private val COLD_DESCRIPTIONS = setOf(
            "активировать голосовой поиск",
            "голосовой поиск",
            "activate voice search",
            "voice search",
        )
        private const val WARMUP_MS = 150L
        private const val RETRY_MS = 180L
        private const val COLD_RETRY_MS = 900L
        private const val UI_DEBOUNCE_MS = 800L
        private const val TRIGGER_DEBOUNCE_MS = 900L
        private const val NATIVE_SUPPRESS_MS = 4000L
        private const val MAX_ATTEMPTS = 90
        private const val NODE_SCAN_LIMIT = 600
    }
}

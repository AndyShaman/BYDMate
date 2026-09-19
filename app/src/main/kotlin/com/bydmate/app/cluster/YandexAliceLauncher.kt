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
        val packageName = event.packageName?.toString().orEmpty()

        if (shouldSuppressNative(event, packageName)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        if (pending && packageName == YANDEX_PACKAGE) advance()
        if (leftYandex(event, packageName)) scheduleFinishIfStillOutside()
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

        return when (stepExact(roots)) {
            Step.DONE -> true
            Step.PROGRESS -> false
            Step.NONE -> advanceAfterExact(roots)
        }
    }

    private fun advanceAfterExact(roots: List<AccessibilityNodeInfo>): Boolean {
        if (stepDescription(roots) == Step.DONE) return true
        if (stepColdId(roots) == Step.PROGRESS) return false
        stepColdScan(roots)
        return false
    }

    private fun stepExact(roots: List<AccessibilityNodeInfo>): Step {
        val now = SystemClock.elapsedRealtime()
        for (root in roots) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(EXACT_VIEW_ID) }
                .getOrNull().orEmpty()
            for (node in nodes) {
                val coldChain = coldClicks > 0
                val allowed = !coldChain || exactClicks == 0 || now - lastExactClick >= UI_DEBOUNCE_MS
                if (allowed) {
                    val terminal = !coldChain || exactClicks > 0
                    if (click(node, terminal)) {
                        if (coldChain) {
                            exactClicks++
                            lastExactClick = now
                        }
                        if (terminal) {
                            listening = true
                            return Step.DONE
                        }
                        return Step.PROGRESS
                    }
                }
            }
        }
        return Step.NONE
    }

    private fun stepDescription(roots: List<AccessibilityNodeInfo>): Step {
        val node = findNode(roots) {
            val description = it.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            description == "голосовой помощник" || description == "voice assistant"
        } ?: return Step.NONE

        if (!click(node, terminal = true)) return Step.NONE
        listening = true
        return Step.DONE
    }

    private fun stepColdId(roots: List<AccessibilityNodeInfo>): Step {
        if (coldClicks >= MAX_COLD_CLICKS) return Step.NONE
        val now = SystemClock.elapsedRealtime()
        if (coldClicks > 0 && now - lastColdClick < COLD_RETRY_MS) return Step.NONE

        val node = findByViewIds(roots, COLD_VIEW_IDS) ?: return Step.NONE
        if (!click(node, terminal = false)) return Step.NONE

        coldClicks++
        lastColdClick = now
        return Step.PROGRESS
    }

    private fun stepColdScan(roots: List<AccessibilityNodeInfo>): Step {
        if (coldClicks != 0) return Step.NONE
        val node = findNode(roots) {
            val id = it.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val description = it.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            id.contains(COLD_ID_FAMILY) || description in COLD_DESCRIPTIONS
        } ?: return Step.NONE

        if (!click(node, terminal = false)) return Step.NONE
        coldClicks = 1
        lastColdClick = SystemClock.elapsedRealtime()
        return Step.PROGRESS
    }

    private fun findByViewIds(
        roots: List<AccessibilityNodeInfo>,
        ids: List<String>,
    ): AccessibilityNodeInfo? {
        roots.forEach { root ->
            ids.forEach { id ->
                val found = runCatching { root.findAccessibilityNodeInfosByViewId(id) }
                    .getOrNull().orEmpty().firstOrNull()
                if (found != null) return found
            }
        }
        return null
    }

    private fun findNode(
        roots: List<AccessibilityNodeInfo>,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        roots.forEach { root ->
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < NODE_SCAN_LIMIT) {
                val node = queue.removeFirst()
                visited++
                if (matches(node)) return node
                repeat(node.childCount) { index ->
                    runCatching { node.getChild(index) }.getOrNull()?.let(queue::addLast)
                }
            }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo, terminal: Boolean): Boolean {
        val target = clickableTarget(node) ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiClick < UI_DEBOUNCE_MS) return false
        if (!runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
            return false
        }

        lastUiClick = now
        if (terminal) {
            pending = false
            generation++
        }
        return true
    }

    private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !target.isClickable && hops < MAX_PARENT_HOPS) {
            target = runCatching { target.parent }.getOrNull()
            hops++
        }
        return target?.takeIf { it.isClickable }
    }

    private fun yandexRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        addYandexRoot(roots, runCatching { service.rootInActiveWindow }.getOrNull())
        runCatching { service.windows }.getOrNull().orEmpty().forEach { window ->
            addYandexRoot(roots, runCatching { window.root }.getOrNull())
        }
        return roots
    }

    private fun addYandexRoot(
        roots: MutableList<AccessibilityNodeInfo>,
        root: AccessibilityNodeInfo?,
    ) {
        if (root?.packageName?.toString() == YANDEX_PACKAGE) roots += root
    }

    private fun shouldSuppressNative(event: AccessibilityEvent, packageName: String): Boolean =
        SystemClock.elapsedRealtime() < suppressNativeUntil &&
            android.os.Build.VERSION.SDK_INT <= 29 &&
            packageName == BYD_VOICE_PACKAGE &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    private fun leftYandex(event: AccessibilityEvent, packageName: String): Boolean =
        listening &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            packageName.isNotBlank() &&
            packageName != YANDEX_PACKAGE

    private fun scheduleFinishIfStillOutside() {
        handler.postDelayed({
            val active = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (listening && active != YANDEX_PACKAGE) finish()
        }, EXIT_GRACE_MS)
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

    private enum class Step { NONE, PROGRESS, DONE }

    companion object {
        private const val YANDEX_PACKAGE = "com.yandex.browser"
        private const val BYD_VOICE_PACKAGE = "com.byd.vrassistant"
        private const val EXACT_VIEW_ID = "com.yandex.browser:id/alice_input_quarknyx"
        private const val COLD_ID_FAMILY = "bro_omnibox_button_microphone"
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
        private const val EXIT_GRACE_MS = 1200L
        private const val MAX_ATTEMPTS = 90
        private const val MAX_COLD_CLICKS = 3
        private const val MAX_PARENT_HOPS = 4
        private const val NODE_SCAN_LIMIT = 600
    }
}

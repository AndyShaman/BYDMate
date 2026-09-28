package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.navdata.NavPackages
import com.bydmate.app.voice.VoiceController
import java.util.ArrayDeque
import java.util.Locale

internal class YandexAliceLauncher(
    private val service: AccessibilityService,
    private val voiceController: () -> VoiceController,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ui = YandexAliceUiDriver(service)

    private var pending = false
    private var generation = 0
    private var attempt = 0
    private var lastTrigger = 0L
    private var listening = false
    private var suppressNativeUntil = 0L

    fun ownsMicButton(): Boolean {
        if (pending || listening) return true
        val active = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (isAliceContextPackage(active.orEmpty())) return true
        return runCatching {
            service.windows.any { window ->
                isAliceContextPackage(window.root?.packageName?.toString().orEmpty())
            }
        }.getOrDefault(false)
    }

    fun trigger() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < TRIGGER_DEBOUNCE_MS) return
        lastTrigger = now
        suppressNativeUntil = now + NATIVE_SUPPRESS_MS

        val localWasActive = voiceController().stopForExternalAssistant()
        if (localWasActive) {
            handler.postDelayed({ startAliceUi() }, LOCAL_RELEASE_MS)
        } else {
            startAliceUi()
        }
    }

    private fun startAliceUi() {
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

        if (pending && isAliceContextPackage(packageName)) advance()
        if (leftYandexWhileListening(listening, event, packageName)) {
            handler.postDelayed({
                val active = runCatching {
                    service.rootInActiveWindow?.packageName?.toString()
                }.getOrNull()
                if (listening && !isAliceContextPackage(active.orEmpty())) finish()
            }, EXIT_GRACE_MS)
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

    private fun advance(): Boolean = when (ui.advance()) {
        AliceUiStep.DONE -> {
            pending = false
            listening = true
            // Idempotent reassertion: if media started during the Yandex warm-up, acquire the
            // field-proven physical duck now. If the startAliceUi() duck already owns the saved
            // volume this only refreshes its safety timeout.
            voiceController().beginExternalAssistantAudio()
            generation++
            true
        }
        AliceUiStep.PROGRESS, AliceUiStep.NONE -> false
    }

    private fun shouldSuppressNative(event: AccessibilityEvent, packageName: String): Boolean =
        SystemClock.elapsedRealtime() < suppressNativeUntil &&
            android.os.Build.VERSION.SDK_INT <= 29 &&
            packageName == BYD_VOICE_PACKAGE &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED


    private fun resetAttempt() {
        handler.removeCallbacksAndMessages(null)
        pending = false
        attempt = 0
        listening = false
        ui.resetAttempt()
        generation++
    }

    private fun finish() {
        pending = false
        listening = false
        generation++
        voiceController().endExternalAssistantAudio()
    }
}

private class YandexAliceUiDriver(
    private val service: AccessibilityService,
) {
    private var coldClicks = 0
    private var lastColdClick = 0L
    private var lastUiClick = 0L

    fun resetAttempt() {
        coldClicks = 0
        lastColdClick = 0L

    }

    fun advance(): AliceUiStep {
        val roots = yandexRoots(service)
        if (roots.isEmpty()) return AliceUiStep.NONE
        return when (val exact = stepExact(roots)) {
            AliceUiStep.NONE -> advanceAfterExact(roots)
            else -> exact
        }
    }

    private fun advanceAfterExact(roots: List<AccessibilityNodeInfo>): AliceUiStep {
        val description = stepDescription(roots)
        if (description == AliceUiStep.DONE) return description
        val coldId = stepColdId(roots)
        if (coldId == AliceUiStep.PROGRESS) return coldId
        return stepColdScan(roots)
    }

    private fun stepExact(roots: List<AccessibilityNodeInfo>): AliceUiStep {
        val node = roots.asSequence().flatMap { root ->
            runCatching { root.findAccessibilityNodeInfosByViewId(EXACT_VIEW_ID) }
                .getOrNull().orEmpty().asSequence()
        }.firstOrNull() ?: return AliceUiStep.NONE

        // One confirmed Alice control click is enough on current Yandex Browser. The old cold-chain
        // logic deliberately clicked it twice; on DiLink 3 that toggled listening ON -> OFF -> ON.
        return if (click(node)) AliceUiStep.DONE else AliceUiStep.NONE
    }

    private fun stepDescription(roots: List<AccessibilityNodeInfo>): AliceUiStep {
        val node = findNode(roots) {
            val description = it.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            description == "голосовой помощник" || description == "voice assistant"
        } ?: return AliceUiStep.NONE
        return if (click(node)) AliceUiStep.DONE else AliceUiStep.NONE
    }

    private fun stepColdId(roots: List<AccessibilityNodeInfo>): AliceUiStep {
        if (coldClicks >= MAX_COLD_CLICKS) return AliceUiStep.NONE
        val now = SystemClock.elapsedRealtime()
        if (coldClicks > 0 && now - lastColdClick < COLD_RETRY_MS) return AliceUiStep.NONE

        val node = findByViewIds(roots, COLD_VIEW_IDS) ?: return AliceUiStep.NONE
        if (!click(node)) return AliceUiStep.NONE

        coldClicks++
        lastColdClick = now
        return AliceUiStep.PROGRESS
    }

    private fun stepColdScan(roots: List<AccessibilityNodeInfo>): AliceUiStep {
        if (coldClicks != 0) return AliceUiStep.NONE
        val node = findNode(roots) {
            val id = it.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val description = it.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            id.contains(COLD_ID_FAMILY) || description in COLD_DESCRIPTIONS
        } ?: return AliceUiStep.NONE

        if (!click(node)) return AliceUiStep.NONE
        coldClicks = 1
        lastColdClick = SystemClock.elapsedRealtime()
        return AliceUiStep.PROGRESS
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        val target = clickableTarget(node) ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiClick < UI_DEBOUNCE_MS) return false
        if (!runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
            return false
        }
        lastUiClick = now
        return true
    }
}

private enum class AliceUiStep { NONE, PROGRESS, DONE }

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

private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var target: AccessibilityNodeInfo? = node
    var hops = 0
    while (target != null && !target.isClickable && hops < MAX_PARENT_HOPS) {
        target = runCatching { target.parent }.getOrNull()
        hops++
    }
    return target?.takeIf { it.isClickable }
}

private fun yandexRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
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
    if (root != null && isAliceContextPackage(root.packageName?.toString().orEmpty())) roots += root
}

private const val YANDEX_PACKAGE = "com.yandex.browser"

internal fun isAliceContextPackage(packageName: String): Boolean =
    packageName == YANDEX_PACKAGE || packageName in NavPackages.YANDEX_NAVI
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
private const val LOCAL_RELEASE_MS = 220L
private fun leftYandexWhileListening(
    listening: Boolean,
    event: AccessibilityEvent,
    packageName: String,
): Boolean {
    if (!listening) return false
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
    if (packageName.isBlank()) return false
    return !isAliceContextPackage(packageName)
}


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

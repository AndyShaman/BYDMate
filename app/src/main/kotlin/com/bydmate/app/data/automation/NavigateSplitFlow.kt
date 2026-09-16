package com.bydmate.app.data.automation

import com.bydmate.app.split.SplitPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One `navigate` dispatch, split-aware and able to press «Поехали» for the driver.
 *
 * Two facts from the car (2026-09-16) shape this:
 *  - the firmware answers ANY activity launch into a task that stands in the split root with
 *    startFullWindow, and the Navigator is recreated twice and loses the route. No intent flag
 *    avoids it. A route built BEFORE the split is switched on, on the other hand, survives.
 *    So the split is ended first, the route is built fullscreen, and the pair is put back.
 *  - «Поехали» is a plain text node in the Navigator's a11y tree, so the route-preview screen
 *    is both the readback that the route was built and the button to start guidance.
 *
 * Everything the flow touches is behind [Env]; the flow itself is pure control logic.
 */
internal class NavigateSplitFlow(
    private val env: Env,
    private val cfg: Config = Config(),
) {

    interface Env {
        /** The pair of the split session standing right now, or null when there is none. */
        fun activeSplitPair(): SplitPair?
        suspend fun exitSplit(): Boolean
        /** Restarts the split; null = restored, otherwise the reason it did not come back. */
        suspend fun restoreSplit(pair: SplitPair): String?
        suspend fun sendIntent(): DispatchResult
        /** The accessibility service is bound — without it nothing can be read or pressed. */
        fun a11yConnected(): Boolean
        fun goButtonVisible(): Boolean
        fun clickGo(): Boolean
        suspend fun wait(ms: Long)
        fun log(line: String)
    }

    data class Config(
        val buttonTimeoutMs: Long = 20_000L,
        val pollMs: Long = 500L,
        val clickGoneTimeoutMs: Long = 5_000L,
        /** How long a preview that was already on screen gets to give way to the new route. */
        val staleGoneTimeoutMs: Long = 5_000L,
        /** Settle time between the route screen and restarting the split. */
        val restoreDelayMs: Long = 1_000L,
    )

    /**
     * @param go             the driver said «поехали», not «построй маршрут»: press the button.
     * @param autoGoSupported this dispatch ends on a Yandex Navigator route screen (2GIS and the
     *                        search/show modes have no «Поехали» node to wait for).
     */
    suspend fun run(go: Boolean, autoGoSupported: Boolean): DispatchResult {
        val pair = env.activeSplitPair()
        if (pair != null) {
            val exitOk = env.exitSplit()
            env.log(
                "navigate split: pair=${pair.narrowPkg}/${pair.widePkg} " +
                    "exit=${if (exitOk) "ok" else "fail"}"
            )
        }
        // The driver's split must not be a casualty of anything below: a failed intent, or the
        // voice session being cancelled while the route screen is awaited.
        var restored = pair == null
        try {
            return sendAndAwait(go, autoGoSupported, pair) { restored = true }
        } catch (e: CancellationException) {
            if (!restored && pair != null) {
                env.log("navigate split: cancelled, restoring the pair")
                withContext(NonCancellable) { restore(pair) }
            }
            throw e
        }
    }

    /** The intent, the route screen and the restore; [onRestore] fires before the pair is put back. */
    private suspend fun sendAndAwait(
        go: Boolean,
        autoGoSupported: Boolean,
        pair: SplitPair?,
        onRestore: () -> Unit,
    ): DispatchResult {
        val stale = autoGoSupported && env.a11yConnected() && env.goButtonVisible()
        if (stale) env.log("auto-go: preview already on screen before the intent")
        val sent = env.sendIntent()
        if (!sent.success) {
            if (pair != null) {
                onRestore()
                restore(pair)
            }
            return sent
        }
        if (go && !autoGoSupported) env.log("auto-go: 2gis не поддерживается")
        val failure = if (autoGoSupported && (go || pair != null)) awaitRoute(go, stale) else null
        if (pair == null) return if (failure == null) sent else DispatchResult(false, failure)
        env.wait(cfg.restoreDelayMs)
        onRestore()
        return restoreVerdict(pair, failure)
    }

    /** Puts the pair back and folds that outcome together with whatever the route screen said. */
    private suspend fun restoreVerdict(pair: SplitPair, failure: String?): DispatchResult {
        val restoreFailure = restore(pair)
        return when {
            restoreFailure != null -> DispatchResult(
                false,
                listOfNotNull(failure, "сплит не восстановлен: $restoreFailure").joinToString("; ")
            )
            failure != null -> DispatchResult(false, "$failure, сплит восстановлен")
            else -> DispatchResult(true, "маршрут построен, сплит восстановлен")
        }
    }

    /** Waits for the route screen and, when [go], presses it. Returns the reason it did not work. */
    private suspend fun awaitRoute(go: Boolean, stale: Boolean): String? {
        if (!env.a11yConnected()) {
            env.log("auto-go: служба специальных возможностей выключена")
            return "не удалось проверить маршрут: служба специальных возможностей выключена"
        }
        if (stale && !awaitStaleGone()) {
            // The old preview never left: its «Поехали» cannot be told from the new route's.
            return if (go) "на экране уже был маршрут, новый не отличить: нажмите Поехали сами" else null
        }
        var waited = 0L
        while (waited < cfg.buttonTimeoutMs && !env.goButtonVisible()) {
            env.wait(cfg.pollMs)
            waited += cfg.pollMs
        }
        if (waited >= cfg.buttonTimeoutMs && !env.goButtonVisible()) {
            env.log("auto-go: button not found in ${cfg.buttonTimeoutMs}ms")
            return "кнопка Поехали не найдена за ${cfg.buttonTimeoutMs / 1000} с"
        }
        env.log("auto-go: go=$go button seen after ${waited}ms")
        return if (go) pressGo() else null
    }

    /** A preview was on screen before the intent: wait for it to give way. False = it never did. */
    private suspend fun awaitStaleGone(): Boolean {
        var waited = 0L
        while (waited < cfg.staleGoneTimeoutMs && env.goButtonVisible()) {
            env.wait(cfg.pollMs)
            waited += cfg.pollMs
        }
        val gone = !env.goButtonVisible()
        env.log(
            if (gone) "auto-go: stale preview gone after ${waited}ms"
            else "auto-go: stale preview still on screen after ${waited}ms"
        )
        return gone
    }

    /** Presses «Поехали» and reads the press back: the button is gone on the guidance screen. */
    private suspend fun pressGo(): String? {
        val clicked = env.clickGo()
        var waited = 0L
        var gone = false
        while (clicked && waited < cfg.clickGoneTimeoutMs) {
            env.wait(cfg.pollMs)
            waited += cfg.pollMs
            if (!env.goButtonVisible()) {
                gone = true
                break
            }
        }
        env.log(
            "auto-go: click=${if (clicked) "ok" else "fail"} " +
                "gone_after=${if (gone) "${waited}ms" else "-"}"
        )
        return when {
            !clicked -> "не получилось нажать Поехали"
            !gone -> "нажал Поехали, но ведение не началось"
            else -> null
        }
    }

    private suspend fun restore(pair: SplitPair): String? {
        val failure = env.restoreSplit(pair)
        env.log("navigate split: restore=${failure?.let { "fail $it" } ?: "ok"}")
        return failure
    }
}

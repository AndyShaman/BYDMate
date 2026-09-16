package com.bydmate.app.data.automation

import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitSide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateSplitFlowTest {

    private val pair = SplitPair("ru.yandex.yandexnavi", "com.byd.music", SplitSide.LEFT)

    /**
     * Fake car: the «Поехали» button appears [buttonAfterMs] of waiting after the intent and
     * disappears [goneAfterMs] after the click, both measured on the fake clock the flow drives
     * through [NavigateSplitFlow.Env.wait].
     */
    private class FakeEnv(
        val pair: SplitPair? = null,
        val buttonAfterMs: Long? = 0L,
        val goneAfterMs: Long? = 500L,
        val sendResult: DispatchResult = DispatchResult(true),
        /** A preview from an earlier command is on screen before the intent goes out. */
        val visibleBeforeSend: Boolean = false,
        /** How long that old preview stays after the intent; null = it never leaves. */
        val staleHoldMs: Long? = 0L,
    ) : NavigateSplitFlow.Env {
        /** The fake clock throws cancellation on this wait (1-based); null = never. */
        var cancelOnWait: Int? = null
        var waits = 0
        var a11y = true
        var clickWorks = true
        var restoreFailure: String? = null

        var elapsed = 0L
        var sent = 0
        var exits = 0
        var restores = 0
        var clicks = 0
        var clickedAt: Long? = null
        val log = mutableListOf<String>()

        override fun activeSplitPair(): SplitPair? = pair
        override suspend fun exitSplit(): Boolean {
            exits++
            return true
        }

        override suspend fun restoreSplit(pair: SplitPair): String? {
            restores++
            return restoreFailure
        }

        override suspend fun sendIntent(): DispatchResult {
            sent++
            return sendResult
        }

        override fun a11yConnected(): Boolean = a11y

        override fun goButtonVisible(): Boolean {
            if (sent == 0) return visibleBeforeSend
            if (visibleBeforeSend) {
                val hold = staleHoldMs ?: return true
                if (elapsed < hold) return true
            }
            val appearsAt = buttonAfterMs ?: return false
            if (elapsed < appearsAt) return false
            val clicked = clickedAt ?: return true
            val gone = goneAfterMs ?: return true
            return elapsed < clicked + gone
        }

        override fun clickGo(): Boolean {
            clicks++
            if (clickWorks) clickedAt = elapsed
            return clickWorks
        }

        override suspend fun wait(ms: Long) {
            waits++
            if (waits == cancelOnWait) throw CancellationException("voice session stopped")
            elapsed += ms
        }

        override fun log(line: String) {
            log += line
        }
    }

    @Test
    fun `split active and go true - exit, send, wait, click, restore`() = runTest {
        val env = FakeEnv(pair = pair, buttonAfterMs = 1_000L)

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = true)

        assertTrue(result.success)
        assertEquals("маршрут построен, сплит восстановлен", result.reason)
        assertEquals(1, env.exits)
        assertEquals(1, env.sent)
        assertEquals(1, env.clicks)
        assertEquals(1, env.restores)
        assertTrue(env.log.any { it.startsWith("navigate split: pair=") && it.endsWith("exit=ok") })
        assertTrue(env.log.any { it == "auto-go: go=true button seen after 1000ms" })
        assertTrue(env.log.any { it.startsWith("auto-go: click=ok gone_after=") })
        assertTrue(env.log.contains("navigate split: restore=ok"))
    }

    @Test
    fun `split active and the button never shows - the pair still comes back`() = runTest {
        val env = FakeEnv(pair = pair, buttonAfterMs = null)

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = true)

        assertFalse(result.success)
        assertEquals("кнопка Поехали не найдена за 20 с, сплит восстановлен", result.reason)
        assertEquals(1, env.restores)
        assertEquals(0, env.clicks)
        assertTrue(env.log.contains("auto-go: button not found in 20000ms"))
    }

    @Test
    fun `no split and go false - nothing but the intent`() = runTest {
        val env = FakeEnv(pair = null)

        val result = NavigateSplitFlow(env).run(go = false, autoGoSupported = true)

        assertTrue(result.success)
        assertEquals(1, env.sent)
        assertEquals(0, env.exits)
        assertEquals(0, env.restores)
        assertEquals(0, env.clicks)
    }

    @Test
    fun `2gis with go true - the intent goes out, nothing is pressed`() = runTest {
        val env = FakeEnv(pair = null)

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = false)

        assertTrue(result.success)
        assertEquals(1, env.sent)
        assertEquals(0, env.clicks)
        assertTrue(env.log.contains("auto-go: 2gis не поддерживается"))
    }

    @Test
    fun `no split and go true - waits for the route screen and presses it`() = runTest {
        val env = FakeEnv(pair = null, buttonAfterMs = 1_500L)

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = true)

        assertTrue(result.success)
        assertEquals(1, env.clicks)
        assertEquals(0, env.restores)
    }

    @Test
    fun `accessibility service off - honest verdict, no silent 20 second wait`() = runTest {
        val env = FakeEnv(pair = null).apply { a11y = false }

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = true)

        assertFalse(result.success)
        assertEquals(
            "не удалось проверить маршрут: служба специальных возможностей выключена",
            result.reason,
        )
        assertEquals(0, env.clicks)
    }

    @Test
    fun `a click that leaves the button on screen is not guidance`() = runTest {
        val env = FakeEnv(pair = null, goneAfterMs = null)

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = true)

        assertFalse(result.success)
        assertEquals("нажал Поехали, но ведение не началось", result.reason)
        assertTrue(env.log.any { it == "auto-go: click=ok gone_after=-" })
    }

    @Test
    fun `a failed intent restores the split and reports the intent failure`() = runTest {
        val env = FakeEnv(pair = pair, sendResult = DispatchResult(false, "Навигатор не установлен"))

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = true)

        assertFalse(result.success)
        assertEquals("Навигатор не установлен", result.reason)
        assertEquals(1, env.restores)
        assertEquals(0, env.clicks)
    }

    @Test
    fun `a split that does not come back is named in the verdict`() = runTest {
        val env = FakeEnv(pair = pair).apply { restoreFailure = "не удалось запустить окна" }

        val result = NavigateSplitFlow(env).run(go = false, autoGoSupported = true)

        assertFalse(result.success)
        assertEquals("сплит не восстановлен: не удалось запустить окна", result.reason)
    }

    @Test
    fun `cancelled while waiting for the route - the pair still comes back`() = runTest {
        val env = FakeEnv(pair = pair, buttonAfterMs = 5_000L).apply { cancelOnWait = 3 }

        val thrown = runCatching { NavigateSplitFlow(env).run(go = true, autoGoSupported = true) }
            .exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(1, env.restores)
        assertEquals(0, env.clicks)
        assertTrue(env.log.contains("navigate split: cancelled, restoring the pair"))
    }

    @Test
    fun `an old preview on screen - the click waits for it to give way to the new route`() = runTest {
        val env = FakeEnv(pair = null, visibleBeforeSend = true, staleHoldMs = 1_000L, buttonAfterMs = 2_000L)

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = true)

        assertTrue(result.success)
        assertEquals(1, env.clicks)
        assertTrue(env.clickedAt!! >= 2_000L)
        assertTrue(env.log.contains("auto-go: preview already on screen before the intent"))
        assertTrue(env.log.contains("auto-go: stale preview gone after 1000ms"))
    }

    @Test
    fun `an old preview that never leaves is not pressed`() = runTest {
        val env = FakeEnv(pair = pair, visibleBeforeSend = true, staleHoldMs = null)

        val result = NavigateSplitFlow(env).run(go = true, autoGoSupported = true)

        assertFalse(result.success)
        assertEquals(
            "на экране уже был маршрут, новый не отличить: нажмите Поехали сами, сплит восстановлен",
            result.reason,
        )
        assertEquals(0, env.clicks)
        assertEquals(1, env.restores)
        assertTrue(env.log.contains("auto-go: stale preview still on screen after 5000ms"))
    }
}

package com.bydmate.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GrantSelfHealTest {

    @Before
    fun resetHistory() {
        // The ring is static and shared by every grant instance.
        GrantSelfHeal.clearHistory()
    }

    @Test
    fun `already granted - reassert never called`() = runTest {
        var reassertCount = 0
        val heal = GrantSelfHeal(
            name = "test",
            isGranted = { true },
            reassert = { reassertCount++; true },
        )
        heal.ensure("test")
        assertEquals(0, reassertCount)
    }

    @Test
    fun `grant appears after two failures - loop stops early`() = runTest {
        var checkCount = 0
        var reassertCount = 0
        // isGranted returns false on the 1st and 2nd calls (checkCount 0, 1),
        // true on the 3rd call (checkCount 2) — loop stops after exactly 2 reasserts.
        val heal = GrantSelfHeal(
            name = "test",
            isGranted = { checkCount++ >= 2 },
            reassert = { reassertCount++; true },
            retryDelayMs = 1_000L,
        )
        heal.ensure("test")
        assertEquals(2, reassertCount)
    }

    @Test
    fun `never granted - reassert called attempts times`() = runTest {
        var reassertCount = 0
        val heal = GrantSelfHeal(
            name = "test",
            isGranted = { false },
            reassert = { reassertCount++; false },
            retryDelayMs = 1_000L,
        )
        heal.ensure("test")
        assertEquals(GrantSelfHeal.ATTEMPTS, reassertCount)
    }

    @Test
    fun `isGranted throwing is treated as not granted`() = runTest {
        var reassertCount = 0
        val heal = GrantSelfHeal(
            name = "test",
            isGranted = { throw RuntimeException("simulated error") },
            reassert = { reassertCount++; true },
            retryDelayMs = 1_000L,
        )
        // Must not throw — runCatching swallows the exception.
        heal.ensure("test")
        // isGranted always throws -> always not-granted -> reassert called on every attempt.
        assertEquals(GrantSelfHeal.ATTEMPTS, reassertCount)
    }

    @Test
    fun `history records an already-granted run with no reasserts`() = runTest {
        GrantSelfHeal(
            name = "star a11y",
            isGranted = { true },
            reassert = { true },
        ).ensure("startup")

        val events = GrantSelfHeal.history()
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("star a11y", e.name)
        assertEquals("startup", e.reason)
        assertEquals(1, e.tries)
        assertEquals(emptyList<Boolean>(), e.reasserts)
        assertEquals(true, e.granted)
        assertTrue(e.ts > 0L)
    }

    @Test
    fun `history records per-try reassert results of a failing run`() = runTest {
        var reassertCount = 0
        GrantSelfHeal(
            name = "notification listener",
            isGranted = { false },
            // Daemon answers true on the 1st and 3rd try, false otherwise.
            reassert = { reassertCount++ % 2 == 0 },
            retryDelayMs = 1_000L,
        ).ensure("guidance-active")

        val e = GrantSelfHeal.history().single()
        assertEquals("guidance-active", e.reason)
        assertEquals(GrantSelfHeal.ATTEMPTS, e.tries)
        assertEquals(listOf(true, false, true, false, true, false), e.reasserts)
        assertEquals(false, e.granted)
    }

    @Test
    fun `history records the try count of a run that recovers`() = runTest {
        var checkCount = 0
        GrantSelfHeal(
            name = "test",
            isGranted = { checkCount++ >= 2 },
            reassert = { false },
            retryDelayMs = 1_000L,
        ).ensure("wake")

        val e = GrantSelfHeal.history().single()
        // Granted on the 3rd check, so 3 tries and 2 recorded reasserts.
        assertEquals(3, e.tries)
        assertEquals(listOf(false, false), e.reasserts)
        assertEquals(true, e.granted)
    }

    @Test
    fun `history ring keeps only the last HISTORY_SIZE events`() = runTest {
        val total = GrantSelfHeal.HISTORY_SIZE + 4
        repeat(total) { i ->
            GrantSelfHeal(
                name = "test",
                isGranted = { true },
                reassert = { true },
            ).ensure("run-$i")
        }

        val events = GrantSelfHeal.history()
        assertEquals(GrantSelfHeal.HISTORY_SIZE, events.size)
        // Oldest first, so the ring starts at the 5th run and ends at the last one.
        assertEquals("run-4", events.first().reason)
        assertEquals("run-${total - 1}", events.last().reason)
    }

    @Test
    fun `history returns a defensive copy`() = runTest {
        val heal = GrantSelfHeal(
            name = "test",
            isGranted = { true },
            reassert = { true },
        )
        heal.ensure("first")
        val snapshot = GrantSelfHeal.history()

        heal.ensure("second")
        assertEquals(1, snapshot.size)
        assertEquals(2, GrantSelfHeal.history().size)
        assertNotSame(snapshot, GrantSelfHeal.history())
    }
}

package com.bydmate.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GrantSelfHealTest {

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
}

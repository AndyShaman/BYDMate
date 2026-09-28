package com.bydmate.app.data.vehicle

import android.content.pm.PackageManager
import com.bydmate.app.diagnostics.TraceRecorder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** PR #261: the "disable the stock assistant" toggle also covers com.byd.vrassistant, the stock
 *  voice UI of Android 10 head units, only where that package is installed. */
class NativeAssistantTest {

    @get:Rule val trace = TraceRecorder()

    private val helper = mockk<HelperClient>(relaxed = true)

    private fun packageManager(vrassistantState: Int?): PackageManager = mockk<PackageManager>().also {
        if (vrassistantState != null) {
            every { it.getApplicationEnabledSetting("com.byd.vrassistant") } returns vrassistantState
        } else {
            every { it.getApplicationEnabledSetting("com.byd.vrassistant") } throws IllegalArgumentException("Unknown package")
        }
    }

    @Test fun `android 10 - an installed vrassistant is disabled together with autovoice`() = runTest {
        NativeAssistant.setDisabled(
            helper, packageManager(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), disabled = true, legacyHeadUnit = true)

        coVerifyOrder {
            helper.setAppHidden("com.byd.autovoice", true)
            helper.setAppHidden("com.byd.vrassistant", true)
        }
    }

    @Test fun `android 10 - enabling restores a vrassistant we disabled`() = runTest {
        NativeAssistant.setDisabled(
            helper, packageManager(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER), disabled = false, legacyHeadUnit = true)

        coVerify(exactly = 1) { helper.setAppHidden("com.byd.autovoice", false) }
        coVerify(exactly = 1) { helper.setAppHidden("com.byd.vrassistant", false) }
    }

    @Test fun `nothing is called for vrassistant when it is not installed`() = runTest {
        NativeAssistant.setDisabled(helper, packageManager(null), disabled = true, legacyHeadUnit = true)

        coVerify(exactly = 1) { helper.setAppHidden("com.byd.autovoice", true) }
        coVerify(exactly = 0) { helper.setAppHidden("com.byd.vrassistant", any()) }
    }

    @Test fun `newer head units never touch vrassistant`() = runTest {
        NativeAssistant.setDisabled(
            helper, packageManager(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), disabled = true, legacyHeadUnit = false)

        coVerify(exactly = 1) { helper.setAppHidden("com.byd.autovoice", true) }
        coVerify(exactly = 0) { helper.setAppHidden("com.byd.vrassistant", any()) }
    }

    // --- No saved choice (a reinstall after an uninstall): the toggle shows the system state. ---

    private fun states(
        autovoice: Int?,
        vrassistant: Int?,
        engine: Int? = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        tts: Int? = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
    ): PackageManager = mockk<PackageManager>().also { pm ->
        val pkgs = listOf(
            "com.byd.autovoice" to autovoice,
            "com.byd.vrassistant" to vrassistant,
            "com.byd.autovoice.engine" to engine,
            "com.byd.autovoice.tts" to tts,
        )
        for ((pkg, state) in pkgs) {
            if (state != null) every { pm.getApplicationEnabledSetting(pkg) } returns state
            else every { pm.getApplicationEnabledSetting(pkg) } throws IllegalArgumentException("Unknown package")
        }
    }

    @Test fun `a user-disabled autovoice reads as disabled`() {
        val pm = states(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        assertTrue(NativeAssistant.disabledInSystem(pm, legacyHeadUnit = true))
        assertTrue(NativeAssistant.disabledInSystem(pm, legacyHeadUnit = false))
    }

    @Test fun `a user-disabled vrassistant alone reads as disabled on android 10`() {
        val pm = states(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)

        assertTrue(NativeAssistant.disabledInSystem(pm, legacyHeadUnit = true))
    }

    @Test fun `newer head units do not read vrassistant, the toggle never manages it there`() {
        val pm = states(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)

        assertFalse(NativeAssistant.disabledInSystem(pm, legacyHeadUnit = false))
    }

    @Test fun `enabled, missing or unreadable packages read as enabled`() {
        assertFalse(NativeAssistant.disabledInSystem(
            states(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
            legacyHeadUnit = true))
        assertFalse(NativeAssistant.disabledInSystem(states(null, null), legacyHeadUnit = true))
    }

    @Test fun `a user-disabled autovoice engine alone reads as disabled`() {
        val pm = states(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            engine = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)

        assertTrue(NativeAssistant.disabledInSystem(pm, legacyHeadUnit = false))
    }

    @Test fun `a user-disabled autovoice tts alone reads as disabled`() {
        val pm = states(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            tts = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)

        assertTrue(NativeAssistant.disabledInSystem(pm, legacyHeadUnit = false))
    }

    @Test fun `the whole autovoice family enabled reads as enabled`() {
        val pm = states(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        assertFalse(NativeAssistant.disabledInSystem(pm, legacyHeadUnit = false))
    }

    // --- Fix 1: a startup reconciliation and a user toggle must not interleave their calls. ---

    @Test fun `two concurrent setDisabled calls never interleave their setAppHidden calls`() = runTest {
        val order = mutableListOf<Pair<String, Boolean>>()
        val firstCallStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        coEvery { helper.setAppHidden(any(), any()) } coAnswers {
            val pkg = firstArg<String>()
            val disabled = secondArg<Boolean>()
            order.add(pkg to disabled)
            if (pkg == "com.byd.autovoice" && disabled) {
                firstCallStarted.complete(Unit)
                releaseFirst.await()
            }
            true
        }
        val pm = packageManager(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        // First sequence: disable everything, suspended inside its first setAppHidden call.
        val first = async { NativeAssistant.setDisabled(helper, pm, disabled = true, legacyHeadUnit = true) }
        firstCallStarted.await()

        // Second sequence starts while the first is still holding the lock, then finishes.
        val second = async { NativeAssistant.setDisabled(helper, pm, disabled = false, legacyHeadUnit = true) }
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(
            listOf(
                "com.byd.autovoice" to true,
                "com.byd.vrassistant" to true,
                "com.byd.autovoice" to false,
                "com.byd.vrassistant" to false,
            ),
            order,
        )
    }

    @Test fun `a toggle shown on from the system is traced with the disabled packages, an untouched one is not`() {
        NativeAssistant.disabledInSystem(
            states(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER),
            legacyHeadUnit = true)
        NativeAssistant.disabledInSystem(states(null, null), legacyHeadUnit = true)
        NativeAssistant.disabledInSystem(
            states(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
            legacyHeadUnit = true)

        assertEquals(
            listOf("app    native-assistant-from-system packages=com.byd.autovoice,com.byd.vrassistant #1"),
            trace.events(),
        )
    }
}

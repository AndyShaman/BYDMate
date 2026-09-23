package com.bydmate.app.data.backup

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PostRestoreCheckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var state: SharedPreferences
    private val probes = FakeProbes()
    private var bootId: String? = null
    private val check get() = PostRestoreCheck(state, probes) { bootId }

    private class FakeProbes : PostRestoreProbes {
        var toggles = RestoredToggles(
            widget = false, blindSpot = false, voice = false, agent = false, tts = false, ttsOffline = true,
            ttsVoiceId = "dmitri", nativeAssistantDisabled = false,
        )
        var overlay = true
        var daemonGrants = false
        val granted = mutableSetOf<String>()
        var asrReady = true
        val readyVoices = mutableSetOf<String>()
        var attachCalls = 0
        var daemonCalls = 0
        /** When set, toggles() suspends until it completes. */
        var gate: CompletableDeferred<Unit>? = null
        var inToggles = false

        override suspend fun toggles(): RestoredToggles {
            inToggles = true
            gate?.await()
            inToggles = false
            return toggles
        }
        override fun canDrawOverlays() = overlay
        override fun hasPermission(name: String) = name in granted
        override fun asrModelReady() = asrReady
        override fun ttsVoiceReady(voiceId: String) = voiceId in readyVoices
        override suspend fun grantOverlayViaDaemon(): Boolean {
            daemonCalls++
            if (daemonGrants) overlay = true
            return daemonGrants
        }
        override suspend fun attachWidget() { attachCalls++ }
    }

    @Before
    fun setUp() {
        state = context.getSharedPreferences(PostRestoreCheck.PREFS_NAME, Context.MODE_PRIVATE)
        state.edit().clear().commit()
    }

    private fun markPending(bootId: String? = null) = PostRestoreCheck.markPending(context, bootId)
    private fun pending() = state.getBoolean(PostRestoreCheck.KEY_PENDING, false)

    @Test
    fun `no marker - nothing runs`() = runBlocking {
        val c = check
        assertNull(c.runIfPending())
        assertNull(c.report.value)
        assertEquals(0, probes.daemonCalls)
    }

    @Test
    fun `all fine - marker cleared, report empty`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(widget = true)
        val c = check

        val report = c.runIfPending()

        assertTrue(report!!.isEmpty)
        assertNull(c.report.value)
        assertFalse(pending())
        assertFalse(state.contains(PostRestoreCheck.KEY_TS))
    }

    @Test
    fun `overlay missing, daemon grant works, widget on - widget attached, no item`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(widget = true)
        probes.overlay = false
        probes.daemonGrants = true

        val report = check.runIfPending()!!

        assertEquals(1, probes.attachCalls)
        assertFalse(PostRestoreItem.Overlay in report.items)
        assertFalse(pending())
    }

    @Test
    fun `daemon grant fails - overlay item, marker kept`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(agent = true)
        probes.granted += Manifest.permission.READ_CONTACTS
        probes.overlay = false
        val c = check

        val report = c.runIfPending()!!

        assertEquals(listOf(PostRestoreItem.Overlay), report.items)
        assertEquals(report, c.report.value)
        assertEquals(0, probes.attachCalls)
        assertTrue(pending())
    }

    @Test
    fun `overlay missing but no feature needs it - no daemon call, no item`() = runBlocking {
        markPending()
        probes.overlay = false

        val report = check.runIfPending()!!

        assertEquals(0, probes.daemonCalls)
        assertTrue(report.isEmpty)
    }

    @Test
    fun `only blind spot on, overlay missing - overlay item`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(blindSpot = true)
        probes.overlay = false

        val report = check.runIfPending()!!

        assertEquals(1, probes.daemonCalls)
        assertEquals(listOf(PostRestoreItem.Overlay), report.items)
        assertEquals(0, probes.attachCalls)
    }

    @Test
    fun `voice on without mic and model - mic and asr items`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(voice = true)
        probes.asrReady = false

        val report = check.runIfPending()!!

        assertEquals(listOf(PostRestoreItem.Mic, PostRestoreItem.AsrModel), report.items)
    }

    @Test
    fun `agent on without contacts - contacts item`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(agent = true)

        assertEquals(listOf(PostRestoreItem.Contacts), check.runIfPending()!!.items)
    }

    @Test
    fun `tts on without voice files - tts item with the voice id`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(tts = true, ttsVoiceId = "irina")

        assertEquals(listOf(PostRestoreItem.TtsVoice("irina")), check.runIfPending()!!.items)
    }

    @Test
    fun `tts through an online source - no voice item`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(tts = true, ttsOffline = false)

        assertTrue(check.runIfPending()!!.isEmpty)
    }

    @Test
    fun `native assistant disabled - reboot notice keeps the report`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(nativeAssistantDisabled = true)
        val c = check

        val report = c.runIfPending()!!

        assertTrue(report.items.isEmpty())
        assertEquals(setOf(PostRestoreNotice.REBOOT), report.notices)
        assertEquals(report, c.report.value)
        assertTrue(pending())
    }

    @Test
    fun `native assistant disabled, same boot as the restore - reboot notice`() = runBlocking {
        markPending(bootId = "boot-a")
        bootId = "boot-a"
        probes.toggles = probes.toggles.copy(nativeAssistantDisabled = true)

        assertEquals(setOf(PostRestoreNotice.REBOOT), check.runIfPending()!!.notices)
    }

    @Test
    fun `native assistant disabled, rebooted since the restore - no notice, marker cleared`() = runBlocking {
        markPending(bootId = "boot-a")
        bootId = "boot-b"
        probes.toggles = probes.toggles.copy(nativeAssistantDisabled = true)
        val c = check

        assertTrue(c.runIfPending()!!.isEmpty)
        assertNull(c.report.value)
        assertFalse(pending())
        assertFalse(state.contains(PostRestoreCheck.KEY_BOOT_ID))
    }

    @Test
    fun `native assistant disabled, current boot id unknown - reboot notice`() = runBlocking {
        markPending(bootId = "boot-a")
        bootId = null
        probes.toggles = probes.toggles.copy(nativeAssistantDisabled = true)

        assertEquals(setOf(PostRestoreNotice.REBOOT), check.runIfPending()!!.notices)
    }

    @Test
    fun `recheck drops fixed items and clears marker once empty`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(voice = true, widget = true)
        probes.overlay = false
        probes.asrReady = false
        val c = check
        c.runIfPending()

        probes.granted += Manifest.permission.RECORD_AUDIO
        probes.overlay = true
        c.recheck()

        assertEquals(listOf(PostRestoreItem.AsrModel), c.report.value!!.items)
        assertEquals(1, probes.attachCalls)
        assertTrue(pending())

        probes.asrReady = true
        c.recheck()

        assertNull(c.report.value)
        assertFalse(pending())
    }

    @Test
    fun `dismiss clears marker and report`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(voice = true)
        val c = check
        c.runIfPending()

        c.dismiss()

        assertNull(c.report.value)
        assertFalse(pending())
        assertNull(c.runIfPending())
    }

    @Test
    fun `dismiss during a suspended recheck - report stays closed`() = runBlocking {
        markPending()
        probes.toggles = probes.toggles.copy(voice = true)
        val c = check
        c.runIfPending()
        probes.gate = CompletableDeferred()

        val job = launch { c.recheck() }
        while (!probes.inToggles) yield()
        c.dismiss()
        probes.gate!!.complete(Unit)
        job.join()

        assertNull(c.report.value)
        assertFalse(pending())
    }
}

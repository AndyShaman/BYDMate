package com.bydmate.app.ui.settings

import android.content.Context
import com.bydmate.app.data.push.FidPushChannel
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.helper.push.FidPushResult
import com.bydmate.app.hud.HudAmapBroadcaster
import com.bydmate.app.hud.HudController
import com.bydmate.app.hud.HudEventSink
import com.bydmate.app.hud.HudPushLoop
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEndSnapshotTest {

    @Test fun `the end snapshot cannot reach the helper`() {
        val params = RecordingEndSnapshot::class.java.constructors.flatMap { it.parameterTypes.toList() }
        assertTrue(params.none { HelperClient::class.java.isAssignableFrom(it) })
    }

    @Test fun `a blocking live fid push read is never invoked and the totals come from memory`() = runBlocking {
        val push = mockk<FidPushChannel>(relaxed = true)
        every { push.results } returns listOf(FidPushResult(1, 1, FID_PUSH_OK), FidPushResult(2, 1, "ERR"))
        every { push.resubscribes } returns 3
        coEvery { push.diagnosticsSnapshot() } coAnswers { awaitCancellation() }
        val snapshot = RecordingEndSnapshot(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), push)

        val lines = withTimeout(5_000) { snapshot.lines(sinceMs = 0L) }

        coVerify(exactly = 0) { push.diagnosticsSnapshot() }
        assertTrue(lines.toString(), "hud fid: gate=na (no live read at the end; each arm traces its gate)" in lines)
        assertTrue(lines.toString(), "fid push: subscribed=2 ok=1 failed=1" in lines)
        assertTrue(lines.toString(), "fid push: callback: na delivery: na" in lines)
        assertTrue(lines.toString(), "fid push: resubscribes=3" in lines)
    }

    @Test fun `the end snapshot never asks for the probing hud diag`() = runBlocking {
        val hud = mockk<HudController>(relaxed = true)
        every { hud.diag() } throws AssertionError("diag() probes the Amap receiver")
        val snapshot = RecordingEndSnapshot(mockk(relaxed = true), hud, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true))

        val lines = snapshot.lines(sinceMs = 0L)

        verify(exactly = 0) { hud.diag() }
        assertTrue(lines.toString(), lines.any { it.startsWith("hud: status=") })
    }

    @Test fun `the hud counters never evaluate the Amap capability`() {
        var probed = false
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } answers { probed = true; error("probed") }
        val loop = HudPushLoop(mockk<HudEventSink>(relaxed = true), amap = HudAmapBroadcaster(context))

        val diag = HudController.diagOf(loop, channels = null, probeAmap = false)

        assertFalse(probed)
        assertFalse(diag.amapCapable)
    }
}

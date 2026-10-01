package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Закрыть приложение» (#280): a force-stop through the daemon, and every case it refuses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherAppCloseTest {
    private val helper = mockk<HelperClient>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)
    private val dispatcher: ActionDispatcher

    init {
        LocalePreferences(ApplicationProvider.getApplicationContext()).setLanguage("ru")
        every { context.packageManager } returns packageManager
        every { context.packageName } returns SELF
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        every { packageManager.getLaunchIntentForPackage(any()) } returns mockk<Intent>(relaxed = true)
        coEvery { helper.isAlive() } returns true
        coEvery { helper.forceStop(any()) } returns true
        dispatcher = ActionDispatcher(mockk<VehicleApi>(relaxed = true), helper, context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            com.bydmate.app.util.AppStrings(ApplicationProvider.getApplicationContext()), dagger.Lazy { mockk(relaxed = true) })
    }

    private fun close(pkg: String) = ActionDef(
        command = "", displayName = "Закрыть приложение", kind = "app_close",
        payload = """{"packageName":"$pkg","appLabel":"Радио"}""",
    )

    @Test fun `a launchable app is force-stopped through the daemon`() = runBlocking {
        val r = dispatcher.dispatch(close("com.example.radio"), null)
        assertTrue(r.success)
        coVerify(exactly = 1) { helper.forceStop("com.example.radio") }
    }

    @Test fun `BYDMate itself is never closed`() = runBlocking {
        val r = dispatcher.dispatch(close(SELF), null)
        assertFalse(r.success)
        assertEquals("BYDMate не закрывает сам себя", r.reason)
        coVerify(exactly = 0) { helper.forceStop(any()) }
    }

    @Test fun `a package without a launcher entry is refused`() = runBlocking {
        every { packageManager.getLaunchIntentForPackage("com.android.systemui") } returns null
        val r = dispatcher.dispatch(close("com.android.systemui"), null)
        assertFalse(r.success)
        assertEquals("Приложение не установлено: com.android.systemui", r.reason)
        coVerify(exactly = 0) { helper.forceStop(any()) }
    }

    @Test fun `a daemon that is down refuses with the daemon wording`() = runBlocking {
        coEvery { helper.isAlive() } returns false
        val r = dispatcher.dispatch(close("com.example.radio"), null)
        assertFalse(r.success)
        assertEquals("служебный процесс перезапускается", r.reason)
        coVerify(exactly = 0) { helper.forceStop(any()) }
    }

    @Test fun `a force-stop the daemon refused is a failure`() = runBlocking {
        coEvery { helper.forceStop(any()) } returns false
        val r = dispatcher.dispatch(close("com.example.radio"), null)
        assertFalse(r.success)
        assertEquals("Не удалось закрыть приложение: com.example.radio", r.reason)
    }

    @Test fun `no app picked is refused`() = runBlocking {
        val r = dispatcher.dispatch(ActionDef("", "Закрыть приложение", "app_close", """{"packageName":""}"""), null)
        assertFalse(r.success)
        coVerify(exactly = 0) { helper.forceStop(any()) }
    }

    private companion object {
        const val SELF = "com.bydmate.app"
    }
}

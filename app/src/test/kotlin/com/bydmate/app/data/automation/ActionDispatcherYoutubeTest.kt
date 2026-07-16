package com.bydmate.app.data.automation

import android.app.Application
import android.app.SearchManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Real Robolectric Application (not mockk) so getLaunchIntentForPackage and startActivity go
// through the actual framework path, matching the isPackageInstalled/tryStartActivity code
// under test instead of a mocked stand-in for it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherYoutubeTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = ActionDispatcher(vehicleApi, helper, app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true))

    private fun installPackage(pkg: String) {
        val info = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.MainActivity"
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        val resolveInfo = ResolveInfo().apply { activityInfo = info }
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(pkg)
        shadowOf(app.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }

    private fun actionDef(kind: String, payload: String) =
        ActionDef(command = "", displayName = "youtube", kind = kind, payload = payload)

    @Test fun youtube_play_sends_media_play_from_search_to_installed_package() = runTest {
        installPackage("anddea.youtube")
        val res = dispatcher.dispatch(actionDef(kind = "youtube",
            payload = """{"mode":"play","query":"обзор leopard 3"}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, intent.action)
        assertEquals("anddea.youtube", intent.`package`)
        assertEquals("обзор leopard 3", intent.getStringExtra(SearchManager.QUERY))
    }

    @Test fun youtube_search_opens_results_url_in_youtube_app() = runTest {
        installPackage("com.google.android.youtube")   // anddea отсутствует -> fallback
        val res = dispatcher.dispatch(actionDef(kind = "youtube",
            payload = """{"mode":"search","query":"тест драйв"}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.google.android.youtube", intent.`package`)
        assertTrue(intent.dataString!!.startsWith("https://www.youtube.com/results?search_query="))
    }

    @Test fun youtube_prefers_anddea_when_both_builds_installed() = runTest {
        // Pins the priority order itself, not just single-package fallback: with both builds
        // present the ReVanced one (anddea) must win.
        installPackage("com.google.android.youtube")
        installPackage("anddea.youtube")
        val res = dispatcher.dispatch(actionDef(kind = "youtube",
            payload = """{"mode":"play","query":"тест"}"""), null)
        assertTrue(res.success)
        assertEquals("anddea.youtube", shadowOf(app).nextStartedActivity.`package`)
    }

    @Test fun youtube_without_app_fails_with_russian_reason() = runTest {
        val res = dispatcher.dispatch(actionDef(kind = "youtube",
            payload = """{"mode":"play","query":"тест"}"""), null)
        assertFalse(res.success)
        assertTrue(res.reason!!.contains("YouTube"))
    }
}

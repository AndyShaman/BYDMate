package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric so the real Intent/Uri built by the navigate/music/go_home branches
// can be inspected; the daemon-launch tests still use only mocked collaborators.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherLaunchTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)
    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val dispatcher: ActionDispatcher

    init {
        every { context.packageManager } returns packageManager
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        dispatcher = ActionDispatcher(vehicleApi, helper, context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) })
    }

    private fun launchAction(pkg: String) = ActionDef(
        command = "", displayName = "launch", kind = "app_launch",
        payload = """{"packageName":"$pkg"}""",
    )

    /** Dispatch [action] and return the Intent it handed to context.startActivity. */
    private fun dispatchAndCapture(action: ActionDef): Pair<DispatchResult, Intent> {
        val captured = slot<Intent>()
        every { context.startActivity(capture(captured)) } returns Unit
        val result = runBlocking { dispatcher.dispatch(action, null) }
        return result to captured.captured
    }

    @Test fun `app_launch goes through daemon am start when daemon launches it`() = runBlocking {
        every { packageManager.getLaunchIntentForPackage("com.example.app") } returns mockk<Intent>(relaxed = true)
        coEvery { helper.launchApp("com.example.app") } returns true
        val result = dispatcher.dispatch(launchAction("com.example.app"), null)
        assertTrue(result.success)
        coVerify(exactly = 1) { helper.launchApp("com.example.app") }
        // Daemon succeeded → no fallback startActivity.
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test fun `app_launch reports not installed without calling daemon`() = runBlocking {
        every { packageManager.getLaunchIntentForPackage("com.absent.app") } returns null
        val result = dispatcher.dispatch(launchAction("com.absent.app"), null)
        assertFalse(result.success)
        coVerify(exactly = 0) { helper.launchApp(any()) }
    }

    @Test fun `app_launch falls back to startActivity when daemon returns false`() = runBlocking {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.addFlags(any()) } returns intent
        every { packageManager.getLaunchIntentForPackage("com.example.app") } returns intent
        coEvery { helper.launchApp("com.example.app") } returns false
        dispatcher.dispatch(launchAction("com.example.app"), null)
        coVerify(exactly = 1) { helper.launchApp("com.example.app") }
        verify(exactly = 1) { context.startActivity(any()) }
    }

    // --- navigate ---

    @Test fun `navigate by free-text query opens Navigator map search`() {
        val query = "аэропорт Сочи"
        val action = ActionDef(command = "", displayName = "nav", kind = "navigate",
            payload = """{"query":"$query"}""")
        val (result, intent) = dispatchAndCapture(action)
        assertTrue(result.success)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("yandexnavi://map_search?text=${Uri.encode(query)}", intent.dataString)
    }

    @Test fun `navigate by lat lon keeps build_route_on_map deeplink`() {
        val action = ActionDef(command = "", displayName = "nav", kind = "navigate",
            payload = """{"lat":43.4,"lon":39.9}""")
        val (result, intent) = dispatchAndCapture(action)
        assertTrue(result.success)
        assertEquals("yandexnavi://build_route_on_map?lat_to=43.4&lon_to=39.9", intent.dataString)
    }

    // --- yandex_music ---

    @Test fun `yandex_music search launches MEDIA_PLAY_FROM_SEARCH`() {
        val action = ActionDef(command = "", displayName = "music", kind = "yandex_music",
            payload = """{"mode":"search","query":"Кино"}""")
        val (result, intent) = dispatchAndCapture(action)
        assertTrue(result.success)
        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, intent.action)
        assertEquals("ru.yandex.music", intent.`package`)
        assertEquals("Кино", intent.getStringExtra(SearchManager.QUERY))
        assertEquals("vnd.android.cursor.item/*", intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
    }

    @Test fun `yandex_music mybeat keeps radio deeplink`() {
        val action = ActionDef(command = "", displayName = "music", kind = "yandex_music",
            payload = """{"mode":"mybeat"}""")
        val (result, intent) = dispatchAndCapture(action)
        assertTrue(result.success)
        assertEquals("yandexmusic://radio/user/onyourwave?play=true", intent.dataString)
    }

    @Test fun `yandex_music search without query is rejected`() {
        val action = ActionDef(command = "", displayName = "music", kind = "yandex_music",
            payload = """{"mode":"search"}""")
        val result = runBlocking { dispatcher.dispatch(action, null) }
        assertFalse(result.success)
        assertEquals("query не задан", result.reason)
    }

    // Media-session-available success path is covered in ActionDispatcherMusicPlayTest
    // (plain JUnit; mocking MediaController conflicts with Robolectric's shadow of it here).
    @Test fun `yandex_music play without live session falls back to search intent`() {
        dispatcher.activeMediaControllers = { emptyList() }
        val action = ActionDef(command = "", displayName = "music", kind = "yandex_music",
            payload = """{"mode":"play","query":"группа кино"}""")
        val (result, intent) = dispatchAndCapture(action)
        assertTrue(result.success)
        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, intent.action)
        assertEquals("ru.yandex.music", intent.`package`)
        assertEquals("группа кино", intent.getStringExtra(SearchManager.QUERY))
    }

    @Test fun `yandex_music play without query is rejected`() {
        val action = ActionDef(command = "", displayName = "music", kind = "yandex_music",
            payload = """{"mode":"play"}""")
        val result = runBlocking { dispatcher.dispatch(action, null) }
        assertFalse(result.success)
        assertEquals("query не задан", result.reason)
    }

    // --- go_home ---

    @Test fun `go_home starts HOME intent`() {
        val action = ActionDef(command = "", displayName = "home", kind = "go_home", payload = null)
        val (result, intent) = dispatchAndCapture(action)
        assertTrue(result.success)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_HOME) == true)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}

package com.bydmate.app.data.remote

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.RouteNavigatorUris
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.navdata.NavPackages
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AliceAppActionDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcher: ActionDispatcher,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    suspend fun dispatch(json: JSONObject, data: DiParsData?): Result<Unit>? {
        val action = json.optString("action").trim().lowercase()
        packageCandidates(action)?.let { return launch(it, action, data) }
        actionDef(action, json)?.let { return dispatcher.dispatch(it, data).asResult() }
        mediaKey(action)?.let { return dispatchMediaKey(it) }
        return null
    }

    private suspend fun launch(
        candidates: List<String>,
        action: String,
        data: DiParsData?,
    ): Result<Unit> {
        val packageName = candidates.firstOrNull {
            context.packageManager.getLaunchIntentForPackage(it) != null
        } ?: if (action == "app.tiktok.open") findLauncherPackage(TIKTOK_LABELS) else null
            ?: return Result.failure(IllegalStateException("app_not_installed"))

        val payload = JSONObject().put("packageName", packageName).toString()
        return dispatcher.dispatch(ActionDef("", "Alice", "app_launch", payload), data).asResult()
    }

    private fun packageCandidates(action: String): List<String>? = when (action) {
        "app.navigation.open" -> selectedNavigationPackages()
        "app.music.open" -> listOf("ru.yandex.music")
        "app.youtube.open" -> listOf("anddea.youtube", "com.google.android.youtube")
        "app.browser.open" -> listOf("com.yandex.browser")
        "app.car_settings.open" -> listOf("com.byd.carsettings")
        "app.camera.open" -> listOf("com.byd.avc")
        "app.files.open" -> listOf("com.byd.filemanager")
        "app.abrp.open" -> listOf("com.iternio.abrpapp")
        "app.media_center.open" -> listOf("com.byd.mediacenter")
        "app.tiktok.open" -> TIKTOK_PACKAGES
        else -> null
    }

    private fun actionDef(action: String, json: JSONObject): ActionDef? = when (action) {
        "navigation.cluster_on" -> ActionDef("", "Alice", "cluster_projection", "1")
        "navigation.cluster_off" -> ActionDef("", "Alice", "cluster_projection", "0")
        "media.volume_up" -> volumeAction("+1")
        "media.volume_down" -> volumeAction("-1")
        "media.mute" -> volumeAction("mute")
        "media.unmute" -> volumeAction("unmute")
        "media.volume" -> absoluteVolumeAction(json)
        else -> null
    }

    private fun absoluteVolumeAction(json: JSONObject): ActionDef? {
        val percent = json.optInt("value", -1).takeIf { it in 0..100 } ?: return null
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return volumeAction((percent * max / 100.0).toInt().toString())
    }

    private fun volumeAction(value: String) =
        ActionDef("media_volume", "Alice", "media_volume", value)

    private fun mediaKey(action: String): Int? = when (action) {
        "media.play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "media.pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "media.next" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "media.previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        "media.play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> null
    }

    private fun dispatchMediaKey(keyCode: Int): Result<Unit> {
        val now = android.os.SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return Result.success(Unit)
    }

    private fun selectedNavigationPackages(): List<String> {
        val prefs = context.getSharedPreferences(RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE)
        return when (RouteNavigatorUris.normalize(prefs.getString(RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, null))) {
            RouteNavigatorUris.DGIS -> listOf(RouteNavigatorUris.DGIS_PACKAGE)
            RouteNavigatorUris.MAPS -> NavPackages.YANDEX_MAPS.toList()
            RouteNavigatorUris.WAZE -> listOf(RouteNavigatorUris.WAZE_PACKAGE)
            else -> NavPackages.YANDEX_NAVI.toList()
        }
    }

    private fun findLauncherPackage(names: Set<String>): String? {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0).firstNotNullOfOrNull { info ->
            val label = info.loadLabel(context.packageManager)?.toString()?.trim()?.lowercase()
            info.activityInfo?.packageName?.takeIf { label in names }
        }
    }

    private fun com.bydmate.app.data.automation.DispatchResult.asResult(): Result<Unit> =
        if (success) Result.success(Unit) else Result.failure(IllegalStateException(reason ?: "dispatch_failed"))

    companion object {
        private val TIKTOK_PACKAGES = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme",
        )
        private val TIKTOK_LABELS = setOf("tiktok", "tik tok", "тикток", "тик ток")
    }
}

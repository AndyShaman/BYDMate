package com.bydmate.app.data.remote

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.RouteNavigatorUris
import com.bydmate.app.data.local.entity.ActionDef
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

    suspend fun dispatch(json: JSONObject, data: DiParsData): Result<Unit>? {
        val action = json.optString("action").trim().lowercase()

        val packageCandidates = when (action) {
            "app.navigation.open", "app.waze.open" -> navigationPackages()
            "app.music.open" -> listOf("ru.yandex.music")
            "app.youtube.open" -> listOf("anddea.youtube", "com.google.android.youtube")
            "app.browser.open" -> listOf("com.yandex.browser")
            "app.car_settings.open" -> listOf("com.byd.carsettings")
            "app.camera.open" -> listOf("com.byd.avc")
            "app.files.open" -> listOf("com.byd.filemanager")
            "app.abrp.open" -> listOf("com.iternio.abrpapp")
            "app.media_center.open" -> listOf("com.byd.mediacenter")
            "app.tiktok.open" -> listOf(
                "com.zhiliaoapp.musically",
                "com.ss.android.ugc.trill",
                "com.ss.android.ugc.aweme",
            )
            else -> null
        }
        if (packageCandidates != null) {
            val pkg = packageCandidates.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
                ?: if (action == "app.tiktok.open") findLauncherPackage("tiktok", "tik tok", "тикток", "тик ток") else null
                ?: return Result.failure(IllegalStateException("app_not_installed"))
            return dispatcher.dispatch(
                ActionDef("", "Alice", "app_launch", JSONObject().put("packageName", pkg).toString()),
                data,
            ).asResult()
        }

        val actionDef = when (action) {
            "navigation.cluster_on" -> ActionDef("", "Alice", "cluster_projection", "1")
            "navigation.cluster_off" -> ActionDef("", "Alice", "cluster_projection", "0")
            "media.volume_up" -> ActionDef("media_volume", "Alice", "media_volume", "+1")
            "media.volume_down" -> ActionDef("media_volume", "Alice", "media_volume", "-1")
            "media.mute" -> ActionDef("media_volume", "Alice", "media_volume", "mute")
            "media.unmute" -> ActionDef("media_volume", "Alice", "media_volume", "unmute")
            "media.volume" -> {
                val value = json.optInt("value", -1)
                if (value !in 0..100) return Result.failure(IllegalArgumentException("invalid_volume"))
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val level = (value * max / 100.0).toInt()
                ActionDef("media_volume", "Alice", "media_volume", level.toString())
            }
            else -> null
        }
        if (actionDef != null) return dispatcher.dispatch(actionDef, data).asResult()

        val keyCode = when (action) {
            "media.play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "media.pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "media.next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "media.previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "media.play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return null
        }
        val now = android.os.SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return Result.success(Unit)
    }

    private fun navigationPackages(): List<String> {
        val selected = context.getSharedPreferences(RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, RouteNavigatorUris.YANDEX)
        return when (RouteNavigatorUris.normalize(selected)) {
            RouteNavigatorUris.DGIS -> listOf(RouteNavigatorUris.DGIS_PACKAGE, RouteNavigatorUris.YANDEX_PACKAGE)
            else -> listOf(RouteNavigatorUris.YANDEX_PACKAGE, RouteNavigatorUris.DGIS_PACKAGE)
        }
    }

    private fun findLauncherPackage(vararg names: String): String? {
        val wanted = names.map { it.lowercase() }.toSet()
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0).firstNotNullOfOrNull { info ->
            val label = info.loadLabel(context.packageManager)?.toString()?.trim()?.lowercase()
            info.activityInfo?.packageName?.takeIf { label in wanted }
        }
    }

    private fun com.bydmate.app.data.automation.DispatchResult.asResult(): Result<Unit> =
        if (success) Result.success(Unit) else Result.failure(IllegalStateException(reason ?: "dispatch_failed"))
}

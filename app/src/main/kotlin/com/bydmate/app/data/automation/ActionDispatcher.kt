package com.bydmate.app.data.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsControlClient
import com.bydmate.app.data.remote.DiParsData
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class DispatchResult(val success: Boolean, val reason: String? = null)

@Singleton
class ActionDispatcher @Inject constructor(
    private val controlClient: DiParsControlClient,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ActionDispatcher"
        private const val CHANNEL_SILENT_ID = "bydmate_automation_silent"
        private const val CHANNEL_SOUND_ID = "bydmate_automation_sound"
        private const val USER_NOTIF_BASE_ID = 10000
        private const val MINIMIZE_DELAY_MS = 3000L
        private const val AUTO_DIAL_DELAY_MS = 300L
        private const val BT_CALL_PACKAGE = "com.byd.bluetoothcall"
        private const val BT_CALL_ACTION_DIAL_HANGUP = "com.byd.btcall.action.DIAL_HANGUP"
        private const val BT_CALL_KEYCODE_DIAL = 313
        // Hard cap on user-set delay action; protects against typos like "60000000".
        private const val MAX_DELAY_MS = 30_000L
        private val BLOCKED_PATTERNS = listOf("发送CAN", "执行SHELL", "下电")
    }

    private val notifCounter = AtomicInteger(USER_NOTIF_BASE_ID)

    init {
        createUserChannels()
    }

    suspend fun dispatch(action: ActionDef, data: DiParsData?): DispatchResult = try {
        when (action.kind) {
            "param" -> dispatchParam(action, data)
            "notification_silent" -> showNotification(action, silent = true)
            "notification_sound" -> showNotification(action, silent = false)
            "app_launch" -> launchApp(action)
            "call" -> dial(action)
            "navigate" -> navigate(action)
            "url" -> openUrl(action)
            "yandex_music" -> launchYandexMusic(action)
            "delay" -> dispatchDelay(action)
            else -> DispatchResult(false, "Unknown action kind: ${action.kind}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "dispatch failed for kind=${action.kind}: ${e.message}")
        DispatchResult(false, e.message ?: "Unknown error")
    }

    private suspend fun dispatchDelay(action: ActionDef): DispatchResult {
        val ms = action.payload?.toLongOrNull()
            ?: return DispatchResult(false, "Длительность паузы не задана")
        if (ms < 0 || ms > MAX_DELAY_MS) {
            return DispatchResult(false, "Длительность паузы вне диапазона (0..${MAX_DELAY_MS} мс)")
        }
        kotlinx.coroutines.delay(ms)
        return DispatchResult(true)
    }

    // --- param (D+ sendCmd) ---

    private suspend fun dispatchParam(action: ActionDef, data: DiParsData?): DispatchResult {
        val blockReason = getBlockReason(action.command, data)
        if (blockReason != null) {
            Log.w(TAG, "Blocked '${action.command}': $blockReason")
            return DispatchResult(false, blockReason)
        }
        val success = controlClient.sendCommand(action.command)
        return DispatchResult(success, if (!success) "sendCmd failed" else null)
    }

    private fun getBlockReason(command: String, data: DiParsData?): String? {
        if (BLOCKED_PATTERNS.any { command.contains(it) }) return "Запрещённая команда"
        if (data == null) return null
        if (isWindowOpenCommand(command)) {
            val speed = data.speed ?: return "Скорость неизвестна"
            if (speed > 80) return "Открытие окон заблокировано на скорости ${speed} км/ч (>80)"
        }
        return null
    }

    private fun isWindowOpenCommand(command: String): Boolean {
        val subjects = listOf("车窗", "天窗", "主驾", "副驾", "后左", "后右", "遮阳帘")
        val openWords = listOf("全开", "半开", "打开", "通风")
        val isWindow = subjects.any { command.contains(it) }
        val isOpen = openWords.any { command.contains(it) }
        return isWindow && isOpen && !command.contains("关")
    }

    // --- notifications (user-visible) ---

    private suspend fun showNotification(action: ActionDef, silent: Boolean): DispatchResult {
        val payload = parsePayload(action.payload)
        val title = payload?.optString("title")?.takeIf(String::isNotBlank) ?: action.displayName
        val text = payload?.optString("text") ?: ""

        if (!silent && com.bydmate.app.ui.overlay.OverlayNotificationManager.canShow(context)) {
            val shown = com.bydmate.app.ui.overlay.OverlayNotificationManager.show(context, title, text)
            if (shown) return DispatchResult(true)
        }

        val channelId = if (silent) CHANNEL_SILENT_ID else CHANNEL_SOUND_ID
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = notifCounter.incrementAndGet()
        nm().notify(id, notif)
        return DispatchResult(true)
    }

    private fun createUserChannels() {
        val silent = NotificationChannel(
            CHANNEL_SILENT_ID,
            "Automation Silent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            description = "Silent automation notifications"
        }
        val sound = NotificationChannel(
            CHANNEL_SOUND_ID,
            "Automation Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Audible automation notifications"
        }
        val manager = nm()
        manager.createNotificationChannel(silent)
        manager.createNotificationChannel(sound)
    }

    // --- external activities ---

    private suspend fun launchApp(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val pkg = payload?.optString("packageName")?.takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "packageName не задан")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return DispatchResult(false, "Приложение не установлено: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = tryStartActivity(intent, "app_launch:$pkg")
        if (result.success) maybeMinimize(payload)
        return result
    }

    private suspend fun dial(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val phone = payload?.optString("phone")?.takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "phone не задан")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = tryStartActivity(intent, "dial:$phone")
        if (result.success && payload.optBoolean("autoDial", false)) {
            kotlinx.coroutines.delay(AUTO_DIAL_DELAY_MS)
            val press = Intent(BT_CALL_ACTION_DIAL_HANGUP).apply {
                setPackage(BT_CALL_PACKAGE)
                putExtra("keycode", BT_CALL_KEYCODE_DIAL)
            }
            try {
                context.sendBroadcast(press)
            } catch (e: Exception) {
                Log.w(TAG, "autoDial broadcast failed: ${e.message}")
            }
        }
        return result
    }

    private fun navigate(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload) ?: return DispatchResult(false, "payload не задан")
        val lat = payload.optDouble("lat", Double.NaN)
        val lon = payload.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return DispatchResult(false, "lat/lon не заданы")
        val uri = "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStartActivity(intent, "navigate:$lat,$lon")
    }

    private suspend fun openUrl(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val url = payload?.optString("url")?.takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "url не задан")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = tryStartActivity(intent, "url:$url")
        if (result.success) maybeMinimize(payload)
        return result
    }

    private suspend fun launchYandexMusic(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val mode = payload?.optString("mode")?.takeIf(String::isNotBlank) ?: "mybeat"
        val deeplink = when (mode) {
            "mybeat" -> "yandexmusic://radio/user/onyourwave?play=true"
            else -> return DispatchResult(false, "Неизвестный режим Я.Музыки: $mode")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deeplink))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = tryStartActivity(intent, "yandex_music:$mode")
        if (result.success) maybeMinimize(payload)
        return result
    }

    private suspend fun maybeMinimize(payload: JSONObject?) {
        if (payload?.optBoolean("minimize", false) != true) return
        kotlinx.coroutines.delay(MINIMIZE_DELAY_MS)
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(home)
        } catch (e: Exception) {
            Log.w(TAG, "home failed: ${e.message}")
        }
    }

    private fun tryStartActivity(intent: Intent, label: String): DispatchResult = try {
        context.startActivity(intent)
        DispatchResult(true)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "$label: ${e.message}")
        DispatchResult(false, "Нет приложения для обработки: ${e.message}")
    } catch (e: SecurityException) {
        Log.w(TAG, "$label (security): ${e.message}")
        DispatchResult(false, "Нет разрешения: ${e.message}")
    }

    // --- helpers ---

    private fun parsePayload(payload: String?): JSONObject? {
        if (payload.isNullOrBlank()) return null
        return try { JSONObject(payload) } catch (e: Exception) { null }
    }

    private fun nm(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}

package com.bydmate.app.ui.automation

import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.RuleShare
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.data.telegram.TELEGRAM_REPORT_KIND
import org.json.JSONObject

// --- Action kind helpers (v2.3.0) ---

fun newNotificationAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_notification),
    kind = "notification",
    payload = """{"title":"","text":""}"""
)

fun ActionDef.notificationTitle(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("title")
} catch (e: Exception) { "" }

fun ActionDef.notificationText(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("text")
} catch (e: Exception) { "" }

fun ActionDef.withNotification(title: String, text: String): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("title", title)
        put("text", text)
    }.toString()
)

// --- App launch helpers (v2.3.0) ---

fun newAppLaunchAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_launch_app),
    kind = "app_launch",
    payload = """{"packageName":"","appLabel":""}"""
)

fun ActionDef.appLaunchPackageName(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("packageName")
} catch (e: Exception) { "" }

fun ActionDef.appLaunchLabel(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("appLabel")
} catch (e: Exception) { "" }

fun ActionDef.appLaunchMinimize(): Boolean = try {
    org.json.JSONObject(payload ?: "{}").optBoolean("minimize", false)
} catch (e: Exception) { false }

fun ActionDef.withAppLaunch(packageName: String, appLabel: String, minimize: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("minimize", minimize)
    }.toString()
)

// --- App close helpers (#280): same payload as app launch, without minimize ---

fun newAppCloseAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.automation_action_app_close),
    kind = "app_close",
    payload = """{"packageName":"","appLabel":""}"""
)

fun ActionDef.withAppClose(packageName: String, appLabel: String): ActionDef = copy(
    payload = org.json.JSONObject().put("packageName", packageName).put("appLabel", appLabel).toString()
)

// --- Media key helpers (#212, #275): payload "play" or "pause" ---

fun newMediaKeyAction(context: Context, key: String): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(
        if (key == "play") R.string.automation_action_media_play else R.string.automation_action_media_pause
    ),
    kind = "media_key",
    payload = key
)

// --- Call helpers (v2.3.0) ---

fun newCallAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_call),
    kind = "call",
    payload = """{"phone":""}"""
)

fun ActionDef.callPhone(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("phone")
} catch (e: Exception) { "" }

fun ActionDef.callName(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("name")
} catch (e: Exception) { "" }

fun ActionDef.callAutoDial(): Boolean = try {
    org.json.JSONObject(payload ?: "{}").optBoolean("autoDial", false)
} catch (e: Exception) { false }

fun ActionDef.withCall(phone: String, name: String, autoDial: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("phone", phone)
        put("name", name)
        put("autoDial", autoDial)
    }.toString()
)

// --- Navigate helpers (v2.3.0) ---

fun newNavigateAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_navigate),
    kind = "navigate",
    payload = """{"lat":0,"lon":0,"name":""}"""
)

fun ActionDef.navigateLat(): Double? = try {
    val d = org.json.JSONObject(payload ?: "{}").optDouble("lat", Double.NaN)
    if (d.isNaN()) null else d
} catch (e: Exception) { null }

fun ActionDef.navigateLon(): Double? = try {
    val d = org.json.JSONObject(payload ?: "{}").optDouble("lon", Double.NaN)
    if (d.isNaN()) null else d
} catch (e: Exception) { null }

fun ActionDef.navigateName(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("name")
} catch (e: Exception) { "" }

fun ActionDef.withNavigate(lat: Double, lon: Double, name: String): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        put("name", name)
    }.toString()
)

// --- URL helpers (v2.3.0) ---

fun newUrlAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_open_url),
    kind = "url",
    payload = """{"url":""}"""
)

fun ActionDef.urlString(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("url")
} catch (e: Exception) { "" }

fun ActionDef.urlMinimize(): Boolean = try {
    org.json.JSONObject(payload ?: "{}").optBoolean("minimize", false)
} catch (e: Exception) { false }

/**
 * The url action with a new address and minimize flag. An unchanged address keeps a
 * `paramsStripped` marker (an import may have carried one); a changed address drops it, since a
 * freshly typed one was never stripped by this build.
 */
fun ActionDef.withUrl(url: String, minimize: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("url", url)
        put("minimize", minimize)
        if (url == urlString()) {
            val stripped = try { org.json.JSONObject(payload ?: "{}").optBoolean(RuleShare.PARAMS_STRIPPED, false) } catch (e: Exception) { false }
            if (stripped) put(RuleShare.PARAMS_STRIPPED, true)
        }
    }.toString()
)

// --- Yandex Music helpers (v2.3.0) ---

fun newYandexMusicAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_yandex_music),
    kind = "yandex_music",
    payload = """{"mode":"mybeat","minimize":true}"""
)

fun ActionDef.yandexMusicMode(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("mode")
} catch (e: Exception) { "" }

fun ActionDef.yandexMusicMinimize(): Boolean = try {
    org.json.JSONObject(payload ?: "{}").optBoolean("minimize", true)
} catch (e: Exception) { true }

fun ActionDef.withYandexMusic(mode: String, minimize: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("mode", mode)
        put("minimize", minimize)
    }.toString()
)

// --- Sentry helpers ---

fun newSentryAction(context: Context): ActionDef = ActionDef(
    command = "sentry",
    displayName = context.getString(R.string.automation_action_sentry),
    kind = "sentry",
    payload = "1"
)

// --- Hotspot helpers ---

fun newHotspotAction(context: Context): ActionDef = ActionDef(
    command = "hotspot",
    displayName = context.getString(R.string.automation_action_hotspot),
    kind = "hotspot",
    payload = "1"
)

// --- Screen backlight helpers (#275): payload "1" on, "0" off ---

fun newScreenAction(context: Context, on: Boolean): ActionDef = ActionDef(
    command = "screen",
    displayName = context.getString(
        if (on) R.string.automation_action_screen_on else R.string.automation_action_screen_off
    ),
    kind = "screen",
    payload = if (on) "1" else "0"
)

// --- Cluster projection helpers ---

fun newClusterAction(context: Context): ActionDef = ActionDef(
    command = "cluster_projection",
    displayName = context.getString(R.string.automation_action_cluster_projection),
    kind = "cluster_projection",
    payload = "1"
)

/**
 * A kind that has its own control row AND a toggle target: sentry and the cluster projection.
 * Their row shows three states, and «Переключить» stores a `toggle` action on [toggleTarget]
 * instead of the row's own [kind], so the same target has one storage shape everywhere.
 */
data class ToggleRowSpec(val kind: String, val command: String, val toggleTarget: String)

val SENTRY_ROW = ToggleRowSpec("sentry", "sentry", ActionDispatcher.TOGGLE_SENTRY)
val CLUSTER_ROW = ToggleRowSpec("cluster_projection", "cluster_projection", ActionDispatcher.TOGGLE_CLUSTER)

/**
 * The three-state row that edits [action], or null when it belongs to another control. A stored
 * toggle on one of these targets comes back to its own row, so «Вкл» and «Выкл» stay reachable.
 */
fun toggleRowSpecFor(action: ActionDef): ToggleRowSpec? = listOf(SENTRY_ROW, CLUSTER_ROW).firstOrNull {
    action.kind == it.kind || (action.kind == "toggle" && action.payload == it.toggleTarget)
}

/** What the «Вкл» / «Выкл» chips store: the row's own kind again, even after a «Переключить». */
fun rowStateAction(spec: ToggleRowSpec, payload: String, displayName: String): ActionDef =
    ActionDef(command = spec.command, displayName = displayName, kind = spec.kind, payload = payload)

/** "Переключить: <цель>" — the saved display name for a toggle action. */
/** [trigger] with the catalog's name of its param and value; anything off the catalog as it is. */
internal fun withCatalogName(trigger: TriggerDef, context: Context): TriggerDef {
    if (trigger.kind != "param") return trigger
    val option = TRIGGER_PARAMS.firstOrNull { it.param == trigger.param } ?: return trigger
    val value = option.localizedEnumLabel(trigger.value, context)
    return trigger.copy(displayName = "${option.localizedName(context)} ${trigger.operator} $value")
}

/** [action] with the catalog's name of its command or toggle target; anything else as it is. */
internal fun withCatalogName(action: ActionDef, context: Context): ActionDef = when (action.kind) {
    "toggle" -> action.payload?.let { action.copy(displayName = toggleDisplayName(context, it)) } ?: action
    "param" -> ACTION_COMMANDS.firstOrNull { it.toggleTarget == null && it.command == action.command }
        ?.let { action.copy(displayName = it.localizedName(context)) }
        ?: levelActionName(action.command, context.appLocalizedContext())?.let { action.copy(displayName = it) }
        ?: action
    else -> action
}

fun toggleDisplayName(context: Context, target: String): String {
    val lc = context.appLocalizedContext()
    val nameRes = ActionDispatcher.toggleTargetNameRes(target) ?: return lc.getString(R.string.automation_action_toggle)
    return lc.getString(R.string.automation_action_toggle_display_name, lc.getString(nameRes))
}

// --- Speak helpers (v3.6) ---

fun newSpeakAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_speak),
    kind = "speak",
    payload = """{"text":""}"""
)

fun ActionDef.speakText(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("text")
} catch (e: Exception) { "" }

fun ActionDef.withSpeakText(text: String): ActionDef = copy(
    payload = org.json.JSONObject().apply { put("text", text) }.toString()
)

// --- Agent query helpers (v3.6) ---

fun newAgentQueryAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_agent_query),
    kind = "agent_query",
    payload = """{"prompt":""}"""
)

fun ActionDef.agentPrompt(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("prompt")
} catch (e: Exception) { "" }

fun ActionDef.withAgentPrompt(prompt: String): ActionDef = copy(
    payload = org.json.JSONObject().apply { put("prompt", prompt) }.toString()
)

// --- Telegram report helpers (3.19) ---

fun newTelegramReportAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.appLocalizedContext().getString(R.string.automation_action_tg_report),
    kind = TELEGRAM_REPORT_KIND,
    payload = telegramReportPayload(ReportField.DEFAULT, ""),
)

internal fun telegramReportPayload(fields: Set<ReportField>, text: String): String =
    org.json.JSONObject().put("fields", ReportField.toJson(fields)).put("text", text).toString()

fun ActionDef.reportFields(): Set<ReportField> = try {
    ReportField.fromJson(org.json.JSONObject(payload ?: "{}").optJSONArray("fields"))
} catch (e: Exception) { emptySet() }

fun ActionDef.reportText(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("text")
} catch (e: Exception) { "" }

fun ActionDef.withTelegramReport(fields: Set<ReportField>, text: String): ActionDef =
    copy(payload = telegramReportPayload(fields, text))

// --- Split screen helpers ---

fun newSplitScreenAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_split_screen),
    kind = "split_screen",
    payload = """{"narrow":"","wide":"","narrowLabel":"","wideLabel":"","side":"right"}"""
)

/** "Close split screen" — no payload: the action always targets the running session. */
fun newSplitScreenCloseAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_split_screen_close),
    kind = "split_screen_close",
    payload = null
)

/** "Toggle split screen" — no payload: exits a running session, else restores the last pair. */
fun newSplitScreenToggleAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_split_screen_toggle),
    kind = "split_screen_toggle",
    payload = null
)

fun ActionDef.splitNarrowPkg(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("narrow")
} catch (e: Exception) { "" }

fun ActionDef.splitWidePkg(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("wide")
} catch (e: Exception) { "" }

fun ActionDef.splitNarrowLabel(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("narrowLabel")
} catch (e: Exception) { "" }

fun ActionDef.splitWideLabel(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("wideLabel")
} catch (e: Exception) { "" }

/** Returns the narrow-pane side: "left" or "right". Defaults to "right" when missing. */
fun ActionDef.splitSide(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("side", "right").let {
        if (it in listOf("left", "right")) it else "right"
    }
} catch (e: Exception) { "right" }

fun ActionDef.withSplitScreen(
    narrowPkg: String,
    narrowLabel: String,
    widePkg: String,
    wideLabel: String,
    side: String,
): ActionDef {
    val displayName = if (narrowLabel.isNotBlank() && wideLabel.isNotBlank()) {
        "$narrowLabel / $wideLabel"
    } else {
        // Caller will resolve the localized default from context; use stable English here.
        "Split Screen"
    }
    return copy(
        displayName = displayName,
        payload = org.json.JSONObject().apply {
            put("narrow", narrowPkg)
            put("wide", widePkg)
            put("narrowLabel", narrowLabel)
            put("wideLabel", wideLabel)
            put("side", side)
        }.toString()
    )
}

/**
 * Pure list reorder used by the automation editor's up/down arrows: returns a new
 * list with the item at [index] swapped with its neighbour. No-op (returns the same
 * order) when [index] is at the boundary or out of range.
 */
internal fun <T> List<T>.moveItem(index: Int, up: Boolean): List<T> {
    val target = if (up) index - 1 else index + 1
    if (index !in indices || target !in indices) return this
    return toMutableList().apply {
        val tmp = this[index]; this[index] = this[target]; this[target] = tmp
    }
}

/**
 * Builds the "button press N" trigger (widget button N). displayName carries a
 * stable internal label used only for logs/snapshot; the UI renders the
 * localized "Кнопка N" label via stringResource. value holds the button id as a
 * string, matching how every other TriggerDef stores its value. The remaining
 * fields are neutral placeholders so the trigger round-trips through
 * TriggerDef.toJson/fromJson with no schema change.
 */
fun newButtonPressTrigger(buttonId: Int): TriggerDef = TriggerDef(
    param = "button",
    chineseName = "",
    operator = "==",
    value = buttonId.toString(),
    displayName = "Кнопка $buttonId",
    kind = "button_press",
)

/**
 * Builds the "steering-wheel key" trigger. Shaped like [newButtonPressTrigger]: value holds the
 * Android keycode as a string, displayName is the internal log label (the UI renders the localized
 * button name). keyCode 0 means "not assigned yet" — the engine's keycode cache skips it, so a
 * freshly added trigger claims no key until the user learns one.
 */
fun newSteeringKeyTrigger(keyCode: Int): TriggerDef = TriggerDef(
    param = AutomationEngine.TRIGGER_PARAM_STEERING_KEY,
    chineseName = "",
    operator = "==",
    value = keyCode.toString(),
    displayName = "Клавиша $keyCode",
    kind = AutomationEngine.TRIGGER_KIND_STEERING_KEY,
)

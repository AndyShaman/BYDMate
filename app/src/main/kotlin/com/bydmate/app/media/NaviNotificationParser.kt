package com.bydmate.app.media

import android.app.Notification
import android.widget.RemoteViews
import java.lang.reflect.Field

/** Reflection-based parser for the Yandex Navigator guidance notification.
 *  The useful fields (maneuver icon, distance, next street, ETA/remaining lines) live in
 *  custom RemoteViews, not in extras, so we walk Notification.contentView/bigContentView
 *  actions the way YNarrows (github.com/Iampatr/YNarrows) does. The layout is not
 *  contractual and changes with Navigator updates, and Android renamed the private
 *  fields across versions - every step fails soft to an empty result. */
object NaviNotificationParser {

    /** One setText/setImageResource action recorded in a RemoteViews, resource ids resolved to names. */
    data class RvAction(val viewName: String, val method: String, val value: String)

    data class Parsed(
        val maneuver: String?,          // short Russian phrase, e.g. "направо"
        val maneuverResource: String?,  // raw icon resource name, e.g. notification_right_sdl
        val distance: String?,          // distance to maneuver (titleView), e.g. "350 м"
        val street: String?,            // next street (descriptionView)
        val bigTexts: List<String>,     // all bigContentView texts; ETA/remaining live here
    )

    /** resolveName maps a resource id inside the NAVIGATOR's package to its entry name
     *  (view ids and drawable names); return null when unknown. */
    fun parse(notification: Notification, resolveName: (Int) -> String?): Parsed {
        val content = extractActions(extractRemoteViews(notification, "contentView"), resolveName)
        val big = extractActions(extractRemoteViews(notification, "bigContentView"), resolveName)
        return fromActions(content, big)
    }

    /** Calibration dump for logcat: every recognized action from both views. Used when a
     *  Navigator update changes the layout and the mapped fields come back empty. */
    fun dump(notification: Notification, resolveName: (Int) -> String?): String {
        val content = extractActions(extractRemoteViews(notification, "contentView"), resolveName)
        val big = extractActions(extractRemoteViews(notification, "bigContentView"), resolveName)
        return "content=$content big=$big"
    }

    /** Pure mapping from extracted actions; unit-testable without real notifications. */
    fun fromActions(content: List<RvAction>, big: List<RvAction>): Parsed {
        var maneuverRes: String? = null
        var distance: String? = null
        var street: String? = null
        content.forEach { a ->
            when (a.viewName) {
                "primaryIconTinted" -> if (a.method == "setImageResource") maneuverRes = a.value
                "titleView" -> if (a.method == "setText") distance = a.value
                "descriptionView" -> if (a.method == "setText") street = a.value
            }
        }
        val bigTexts = big.filter { it.method == "setText" && it.value.isNotBlank() }.map { it.value }
        return Parsed(
            maneuver = maneuverRes?.let { MANEUVER_PHRASES[it] },
            maneuverResource = maneuverRes,
            distance = distance?.takeUnless { it.isBlank() },
            street = street?.takeUnless { it.isBlank() },
            bigTexts = bigTexts,
        )
    }

    // -- reflection plumbing (all soft-fail) --

    private fun extractRemoteViews(notification: Notification, field: String): RemoteViews? =
        runCatching {
            val f = Notification::class.java.getDeclaredField(field)
            f.isAccessible = true
            f.get(notification) as? RemoteViews
        }.getOrNull()

    private fun extractActions(views: RemoteViews?, resolveName: (Int) -> String?): List<RvAction> {
        if (views == null) return emptyList()
        val actions = runCatching {
            val f = RemoteViews::class.java.getDeclaredField("mActions")
            f.isAccessible = true
            f.get(views) as? ArrayList<*>
        }.getOrNull() ?: return emptyList()
        return actions.mapNotNull { toRvAction(it, resolveName) }
    }

    // Field names differ across Android versions: methodName/value on API <= 30,
    // mMethodName/mValue from 31 (fields may also sit in a superclass). Try both.
    private fun toRvAction(action: Any?, resolveName: (Int) -> String?): RvAction? {
        if (action == null) return null
        return runCatching {
            val methodName = findField(action.javaClass, "methodName", "mMethodName")
                ?.get(action) as? String ?: return null
            if (methodName != "setText" && methodName != "setImageResource") return null
            val viewIdField = findField(action.javaClass, "viewId", "mViewId") ?: return null
            val viewId = viewIdField.getInt(action)
            val raw = findField(action.javaClass, "value", "mValue")?.get(action) ?: return null
            val value = if (methodName == "setImageResource" && raw is Int) {
                resolveName(raw) ?: raw.toString()
            } else raw.toString()
            RvAction(resolveName(viewId) ?: viewId.toString(), methodName, value)
        }.getOrNull()
    }

    /** Searches the class hierarchy for the first of the candidate field names. */
    private fun findField(cls: Class<*>, vararg names: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            for (name in names) {
                runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
            }
            c = c.superclass
        }
        return null
    }

    /** Guidance icon resource name -> short Russian phrase for the voice agent. */
    private val MANEUVER_PHRASES = mapOf(
        "notification_left_sdl" to "налево",
        "notification_right_sdl" to "направо",
        "notification_slight_left_sdl" to "левее",
        "notification_slight_right_sdl" to "правее",
        "notification_hard_left_sdl" to "резко налево",
        "notification_hard_right_sdl" to "резко направо",
        "notification_straight_sdl" to "прямо",
        "notification_enter_roundabout_sdl" to "круговое движение",
        "notification_leave_roundabout_sdl" to "съезд с кольца",
        "notification_exit_left_sdl" to "съезд налево",
        "notification_exit_right_sdl" to "съезд направо",
        "notification_fork_left_sdl" to "держись левее",
        "notification_fork_right_sdl" to "держись правее",
        "notification_uturn_left_sdl" to "разворот",
        "notification_uturn_right_sdl" to "разворот направо",
        "notification_finish_sdl" to "финиш рядом",
        "notification_board_ferry_sdl" to "паром",
    )
}

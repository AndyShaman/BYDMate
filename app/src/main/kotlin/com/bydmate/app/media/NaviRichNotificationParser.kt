package com.bydmate.app.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.bydmate.app.navdata.NavManeuverCodes
import com.bydmate.app.navdata.NavPackages
import java.io.ByteArrayOutputStream

/**
 * Rich parser for Yandex guidance notifications - port of the donor
 * com.unkwn2.yandexhud RemoteViewsParser/RemoteViewsActionExtractor pair.
 * Extracts maneuver, camera alert, distances, ETA and icon PNGs from the
 * notification's RemoteViews. Lives next to the untouched legacy
 * NaviNotificationParser (fleet safety): the legacy path keeps feeding
 * NaviRouteHolder byte-for-byte.
 */
object NaviRichNotificationParser {

    enum class Op { TEXT, IMAGE_RES, BG_RES, VISIBILITY }

    /** One RemoteViews reflection action; view/resource names are lowercased. */
    data class RichAction(val viewName: String, val op: Op, val value: String)

    /** Parsed rich notification content (donor RvNaviInfo minus traffic-light fields).
     *  maneuverGaode: donor maneuverEnum collapsed straight to a GAODE code, null = unknown. */
    data class RichNaviInfo(
        val instruction: String = "",
        val road: String = "",
        val distToManeuverM: Int = 0,
        val totalDistM: Int = 0,
        val arrivalTime: String = "",
        val remainingTimeSec: Int = 0,
        val maneuverPng: ByteArray? = null,
        val cameraAlert: String = "",
        val cameraDistanceM: Int = 0,
        val cameraIconPng: ByteArray? = null,
        val maneuverGaode: Int? = null,
    )

    // Donor RemoteViewsParser regexes (distance regexes match NavGuidanceParser's)
    private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)(?!\S)""", RegexOption.IGNORE_CASE)
    private val DIST_M = Regex("""(\d+)\s*(м|m)(?!\S)""", RegexOption.IGNORE_CASE)
    internal val TIME_HHMM = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val DUR = Regex("""(?:(\d+)\s*ч)?\s*(?:(\d+)\s*мин)?""")

    /**
     * Donor RemoteViewsActionExtractor.extractActions: reads RemoteViews.mActions
     * via reflection. [resolveName] maps resource id -> entry name (any case);
     * names are lowercased here because donor tables are lowercase.
     */
    fun extractRichActions(rv: RemoteViews, resolveName: (Int) -> String?): List<RichAction> {
        return try {
            val actionsField = RemoteViews::class.java.getDeclaredField("mActions").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val actions = actionsField.get(rv) as? ArrayList<Any> ?: return emptyList()
            val out = ArrayList<RichAction>(actions.size)
            for (action in actions) readAction(action, resolveName)?.let { out.add(it) }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Donor buildFromActions: deterministic parse of the raw action list. */
    fun buildFromActions(actions: List<RichAction>): RichNaviInfo {
        var maneuverGaode: Int? = null
        var road: String? = null
        var distStr: String? = null
        var cameraAlert: String? = null
        var cameraDistStr: String? = null

        for (a in actions) {
            when (a.viewName) {
                "primaryicontinted" -> {
                    if (a.op == Op.IMAGE_RES && a.value.isNotEmpty()) {
                        val g = NavManeuverCodes.fromNotificationRes(a.value)
                        if (g != 0) maneuverGaode = g
                    }
                }
                "primaryicon" -> {
                    if (a.op == Op.IMAGE_RES && a.value.isNotEmpty()) {
                        val alert = NavManeuverCodes.roadAlertFromRes(a.value)
                        if (alert.isNotEmpty()) {
                            cameraAlert = alert
                            cameraDistStr = null // reset - distance comes from titleview
                        }
                    }
                }
                "titleview" -> {
                    if (a.op == Op.TEXT && a.value.isNotEmpty() && a.value != "setText") {
                        distStr = a.value
                        // with an active camera alert titleview carries camera distance
                        if (cameraAlert != null) {
                            val d = parseDistanceOrNull(a.value)
                            if (d != null && d > 0) cameraDistStr = a.value
                        }
                    }
                }
                "descriptionview" -> {
                    if (a.op == Op.TEXT && a.value.isNotEmpty() && a.value != "setText" &&
                        !NavManeuverCodes.isServicePhrase(a.value) &&
                        !TIME_HHMM.containsMatchIn(a.value) &&
                        NavManeuverCodes.richPhraseGaode(a.value) == 0
                    ) {
                        road = a.value
                    }
                }
            }
            // Street hidden in titleview: "<distance> · <street>"
            if (road == null && a.viewName == "titleview" && a.op == Op.TEXT && a.value.isNotEmpty()) {
                val sepIdx = a.value.indexOf('·')
                if (sepIdx >= 0) {
                    val street = a.value.substring(sepIdx + 1).trim()
                    if (street.isNotEmpty()) road = street
                }
            }
        }

        return RichNaviInfo(
            road = road ?: "",
            distToManeuverM = distStr?.let { parseDistanceOrNull(it) } ?: 0,
            cameraAlert = cameraAlert ?: "",
            cameraDistanceM = cameraDistStr?.let { parseDistanceOrNull(it) } ?: 0,
            cameraIconPng = null, // filled from render path in mergePreferActions
            maneuverGaode = maneuverGaode,
        )
    }

    /** Donor mergePreferActions: actions path wins for maneuver/camera/road/dist,
     *  render path wins for instruction/totals/arrival/remaining/maneuver PNG.
     *  Camera two-stage: alert only from actions; empty alert kills candidates. */
    fun mergePreferActions(actions: RichNaviInfo?, render: RichNaviInfo?): RichNaviInfo? {
        if (actions == null) return render
        if (render == null) return actions
        return RichNaviInfo(
            instruction = render.instruction.ifEmpty { actions.instruction },
            road = actions.road.ifEmpty { render.road },
            distToManeuverM = actions.distToManeuverM.takeIf { it > 0 } ?: render.distToManeuverM,
            totalDistM = render.totalDistM.takeIf { it > 0 } ?: actions.totalDistM,
            arrivalTime = render.arrivalTime.ifEmpty { actions.arrivalTime },
            remainingTimeSec = render.remainingTimeSec.takeIf { it > 0 } ?: actions.remainingTimeSec,
            maneuverPng = render.maneuverPng ?: actions.maneuverPng,
            cameraAlert = actions.cameraAlert,
            cameraDistanceM = if (actions.cameraAlert.isEmpty()) 0
                else actions.cameraDistanceM.takeIf { it > 0 } ?: render.cameraDistanceM,
            cameraIconPng = if (actions.cameraAlert.isEmpty()) null
                else render.cameraIconPng ?: actions.cameraIconPng,
            maneuverGaode = actions.maneuverGaode ?: render.maneuverGaode,
        )
    }

    /** Spec §5: a parse without any of these signals is a stub - the hub stays untouched. */
    fun hasNaviSignal(rv: RichNaviInfo): Boolean =
        rv.maneuverGaode != null || rv.distToManeuverM > 0 || rv.totalDistM > 0 ||
            rv.road.isNotEmpty() || rv.cameraAlert.isNotEmpty()

    internal fun parseDistanceOrNull(s: String): Int? {
        DIST_KM.find(s)?.let {
            return (it.groupValues[1].replace(',', '.').toDoubleOrNull()?.times(1000))?.toInt()
        }
        DIST_M.find(s)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    internal fun parseDuration(s: String): Int {
        val m = DUR.find(s) ?: return 0
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        return h * 3600 + min * 60
    }

    /** True for duration-like strings ("1 ч 40 мин", "30 мин", "1:30"). */
    internal fun isDurationText(s: String): Boolean {
        val trimmed = s.trim()
        if (trimmed.contains("ч") || trimmed.contains("мин")) return true
        return TIME_HHMM.matches(trimmed)
    }

    private fun readAction(action: Any, resolveName: (Int) -> String?): RichAction? {
        try {
            val fields = collectFields(action.javaClass)
            val viewId = intFieldValue(fields, "viewId", action) ?: return null
            val methodName = stringFieldValue(fields, "methodName", action)
            val viewName = lowerName(resolveName, viewId)
            val value = valueObject(fields, action)

            // Yandex ships the camera badge as Icon(TYPE_RESOURCE) - pull its resId
            val iconResId: Int? = (value as? Icon)
                ?.takeIf { it.type == Icon.TYPE_RESOURCE }?.resId

            return when (methodName) {
                "setImageResource", "setImageResourceAsync", "setBackgroundResource" -> {
                    val resId = (value as? Int) ?: iconResId ?: return null
                    val op = if (methodName == "setBackgroundResource") Op.BG_RES else Op.IMAGE_RES
                    RichAction(viewName, op, lowerName(resolveName, resId))
                }
                "setImageIcon", "setImageIconAsync" -> {
                    val resId = iconResId ?: return null
                    RichAction(viewName, Op.IMAGE_RES, lowerName(resolveName, resId))
                }
                "setText" -> RichAction(viewName, Op.TEXT, (value as? CharSequence)?.toString() ?: "")
                "setVisibility" -> RichAction(viewName, Op.VISIBILITY, ((value as? Int) ?: -1).toString())
                else -> when {
                    value is CharSequence -> RichAction(viewName, Op.TEXT, value.toString())
                    iconResId != null -> RichAction(
                        viewName,
                        if (methodName?.contains("background", true) == true) Op.BG_RES else Op.IMAGE_RES,
                        lowerName(resolveName, iconResId))
                    value is Int -> {
                        val nm = lowerName(resolveName, value)
                        if (nm.isNotEmpty())
                            RichAction(
                                viewName,
                                if (methodName?.contains("background", true) == true) Op.BG_RES else Op.IMAGE_RES,
                                nm)
                        else null
                    }
                    else -> null
                }
            }
        } catch (_: Throwable) {
            return null
        }
    }

    private fun lowerName(resolveName: (Int) -> String?, resId: Int): String =
        if (resId == 0) "" else try { resolveName(resId)?.lowercase() ?: "" } catch (_: Throwable) { "" }

    private fun valueObject(fields: List<java.lang.reflect.Field>, action: Any): Any? {
        fields.firstOrNull { it.name == "value" }?.let {
            return try { it.get(action) } catch (_: Throwable) { null }
        }
        for (f in fields) {
            if (f.name == "viewId" || f.name == "methodName" || f.type.isPrimitive) continue
            val v = try { f.get(action) } catch (_: Throwable) { null }
            if (v is CharSequence || v is Int || v is Icon) return v
        }
        return null
    }

    private fun collectFields(cls: Class<*>): List<java.lang.reflect.Field> {
        val all = mutableListOf<java.lang.reflect.Field>()
        var c: Class<*>? = cls
        while (c != null) {
            for (f in c.declaredFields) {
                f.isAccessible = true
                all += f
            }
            c = c.superclass
        }
        return all
    }

    private fun intFieldValue(fields: List<java.lang.reflect.Field>, name: String, action: Any): Int? {
        val f = fields.firstOrNull {
            it.name == name && (it.type == Int::class.javaPrimitiveType || it.type == Integer::class.java)
        } ?: return null
        return try { f.getInt(action) } catch (_: Throwable) { null }
    }

    private fun stringFieldValue(fields: List<java.lang.reflect.Field>, name: String, action: Any): String? {
        val f = fields.firstOrNull { it.name == name && it.type == String::class.java } ?: return null
        return try { f.get(action) as? String } catch (_: Throwable) { null }
    }

    // -- render path --

    private const val TIMEOUT_MS = 3000L

    private const val F_DIST_TO_MANEUVER = "titleview"
    private const val F_DESCRIPTION = "descriptionview"
    private const val F_TOTAL_DIST = "remainingdistanceview"
    private const val F_ARRIVAL = "timeofarrivalview"
    private const val F_REMAINING = "remainingtimeview"

    private val IMAGE_BLOCKLIST = listOf(
        "traffic_light", "etaprogress", "progress", "action", "eta", "dots", "primaryicon",
    )

    private val ACTION_BUTTON_TEXTS = setOf(
        "завершить маршрут", "отмена", "отменить", "закрыть",
        "без уведомлений", "выключить уведомления", "парковки", "обзор",
        "finish the route", "cancel", "overview", "parking", "close",
        "mute", "turn off notifications",
    )

    data class NamedText(val name: String, val value: String)

    /** Rendered image abstraction: keeps classify JVM-testable, PNG converts lazily. */
    data class NamedImage(val name: String, val width: Int, val height: Int, val png: () -> ByteArray?)

    /**
     * Full donor parse flow: actions path + rendered-view path, merged actions-first.
     * Called from the notification lane thread only - the main-looper render wait
     * (up to TIMEOUT_MS) must never block a binder thread (spec R3).
     */
    fun parse(ctx: Context, n: Notification, pkg: String, resolveName: (Int) -> String?): RichNaviInfo? {
        @Suppress("DEPRECATION")
        val rv = n.bigContentView ?: n.contentView ?: n.headsUpContentView ?: return null

        val fromActions = extractRichActions(rv, resolveName)
            .takeIf { it.isNotEmpty() }
            ?.let { buildFromActions(it) }

        val isMaps = pkg in NavPackages.YANDEX_MAPS
        val fromRender = if (Looper.myLooper() == Looper.getMainLooper()) {
            parseOnMain(ctx, rv, resolveName, isMaps)
        } else {
            var result: RichNaviInfo? = null
            val lock = Object()
            Handler(Looper.getMainLooper()).post {
                result = parseOnMain(ctx, rv, resolveName, isMaps)
                synchronized(lock) { lock.notifyAll() }
            }
            synchronized(lock) {
                try { lock.wait(TIMEOUT_MS) } catch (_: InterruptedException) { }
            }
            result
        }

        return mergePreferActions(fromActions, fromRender)
    }

    private fun parseOnMain(
        ctx: Context,
        rv: RemoteViews,
        resolveName: (Int) -> String?,
        isMaps: Boolean,
    ): RichNaviInfo? = try {
        val parent = FrameLayout(ctx)
        val view = rv.apply(ctx, parent)

        val rawTexts = mutableListOf<Pair<Int, String>>()
        val rawImages = mutableListOf<Pair<Int, Drawable>>()
        walk(view, rawTexts, rawImages)

        fun nameOf(id: Int): String =
            if (id == View.NO_ID) "" else try { resolveName(id)?.lowercase() ?: "" } catch (_: Throwable) { "" }

        val texts = rawTexts.map { NamedText(nameOf(it.first), it.second) }
        val images = rawImages.map { (id, d) ->
            NamedImage(nameOf(id), d.intrinsicWidth, d.intrinsicHeight) { toPng(d) }
        }
        classify(texts, images, isMaps)
    } catch (_: Throwable) {
        null
    }

    private fun walk(
        v: View,
        texts: MutableList<Pair<Int, String>>,
        images: MutableList<Pair<Int, Drawable>>,
    ) {
        when (v) {
            is TextView -> {
                val s = v.text?.toString()?.trim().orEmpty()
                if (s.isNotEmpty()) texts.add(v.id to s)
            }
            is ImageView -> {
                val d = v.drawable
                if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) images.add(v.id to d)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), texts, images)
    }

    /** Donor classify: named fields first, heuristic top-up for the blanks. */
    internal fun classify(texts: List<NamedText>, images: List<NamedImage>, isMaps: Boolean): RichNaviInfo {
        fun byName(n: String) = texts.firstOrNull { it.name == n }?.value

        val titleVal = byName(F_DIST_TO_MANEUVER)
        val descVal = byName(F_DESCRIPTION)
        val totalVal = byName(F_TOTAL_DIST)
        val arrivalByName = byName(F_ARRIVAL)?.let { TIME_HHMM.find(it)?.value }
        val remainingByName = byName(F_REMAINING)?.let { parseDuration(it).takeIf { s -> s > 0 } }

        var instruction = ""
        var road = ""
        if (descVal != null) {
            if (isMaps) {
                instruction = descVal
            } else {
                if (NavManeuverCodes.richPhraseGaode(descVal) != 0) {
                    instruction = descVal
                } else if (!TIME_HHMM.containsMatchIn(descVal) && !isDurationText(descVal) &&
                    !NavManeuverCodes.isServicePhrase(descVal)
                ) {
                    road = descVal
                }
            }
        }

        // Street hidden in titleview: "<distance> · <street>"
        if (road.isEmpty() && titleVal != null) {
            val sepIdx = titleVal.indexOf('·')
            if (sepIdx >= 0) {
                val street = titleVal.substring(sepIdx + 1).trim()
                if (street.isNotEmpty()) road = street
            }
        }

        var distManeuver = titleVal?.let { parseDistanceOrNull(it) } ?: 0
        var distTotal = totalVal?.let { parseDistanceOrNull(it) } ?: 0
        var arrival = arrivalByName ?: ""
        var remaining = remainingByName ?: 0

        // primaryicon during an alert = camera badge; candidate only - applied when
        // the actions path confirms the camera (mergePreferActions two-stage rule)
        val cameraIconCandidate = images.firstOrNull { it.name == "primaryicon" }

        if (distManeuver == 0 || distTotal == 0 || arrival.isEmpty() || remaining == 0 ||
            (road.isEmpty() && instruction.isEmpty())
        ) {
            val values = texts.map { it.value }
            val dists = values.mapNotNull { parseDistanceOrNull(it) }
            if (distManeuver == 0) distManeuver = dists.getOrElse(0) { 0 }
            if (distTotal == 0) distTotal = dists.getOrElse(1) { 0 }
            if (arrival.isEmpty()) arrival = values.firstNotNullOfOrNull { TIME_HHMM.find(it)?.value } ?: ""
            if (remaining == 0) {
                remaining = values.firstNotNullOfOrNull { v ->
                    if (v.contains("мин") || v.contains("ч")) parseDuration(v).takeIf { it > 0 } else null
                } ?: 0
            }

            val textual = values.filter { s ->
                s.any { it.isLetter() } &&
                    parseDistanceOrNull(s) == null &&
                    !TIME_HHMM.containsMatchIn(s) &&
                    ACTION_BUTTON_TEXTS.none { it in s.lowercase() }
            }.sortedByDescending { it.length }

            if (instruction.isEmpty()) {
                instruction = textual.firstOrNull {
                    NavManeuverCodes.richPhraseGaode(it) != 0
                } ?: ""
            }
            if (road.isEmpty()) {
                road = textual.firstOrNull {
                    it != instruction &&
                        !NavManeuverCodes.isServicePhrase(it) &&
                        NavManeuverCodes.richPhraseGaode(it) == 0
                } ?: ""
            }
        }

        if (ACTION_BUTTON_TEXTS.any { it in road.lowercase() } || road == "Навигатор запущен") road = ""

        val byIconName = images.firstOrNull { it.name == "primaryicontinted" }
            ?: images.firstOrNull { "nextmaneuver" in it.name }
        val pngImage = byIconName ?: images
            .filter { im -> IMAGE_BLOCKLIST.none { it in im.name } }
            .minByOrNull { im ->
                if (im.width <= 0 || im.height <= 0) Double.MAX_VALUE
                else kotlin.math.abs(im.width.toDouble() / im.height.toDouble() - 1.0)
            }

        return RichNaviInfo(
            instruction = instruction,
            road = road,
            distToManeuverM = distManeuver,
            totalDistM = distTotal,
            arrivalTime = arrival,
            remainingTimeSec = remaining,
            maneuverPng = pngImage?.png?.invoke(),
            cameraAlert = "",
            cameraDistanceM = 0,
            cameraIconPng = cameraIconCandidate?.png?.invoke(),
        )
    }

    private fun toPng(d: Drawable): ByteArray? = try {
        val isShared = d is BitmapDrawable
        val bmp = if (d is BitmapDrawable) {
            d.bitmap
        } else {
            val w = d.intrinsicWidth.coerceAtLeast(1)
            val h = d.intrinsicHeight.coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                d.setBounds(0, 0, w, h)
                d.draw(c)
            }
        }
        try {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            if (!isShared) bmp.recycle()
        }
    } catch (_: Throwable) {
        null
    }
}

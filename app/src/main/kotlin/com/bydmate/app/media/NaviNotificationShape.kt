package com.bydmate.app.media

import com.bydmate.app.media.NaviNotificationParser.RvAction

/**
 * Field diagnostics (#301): the shape of a Navigator notification that gave no guidance, so a
 * user log shows what a new Navigator version posts. Texts carry streets: only their lengths go
 * into the line; image resource names and method names stay.
 */
internal object NaviNotificationShape {

    @Suppress("LongParameterList") // the three notification texts sit side by side in the line
    fun line(
        kind: String,
        extrasKeys: Set<String>,
        title: String?,
        text: String?,
        subText: String?,
        views: Map<String, List<RvAction>>,
    ): String {
        val head = "nav notif shape [$kind]: extras=[${extrasKeys.sorted().joinToString(",")}] " +
            "title=${len(title)} text=${len(text)} sub=${len(subText)}"
        if (views.isEmpty()) return "$head views=none"
        return head + views.entries.joinToString("") { (field, actions) ->
            " $field=[${actions.joinToString(",", transform = ::action)}]"
        }
    }

    private fun len(s: String?): String = s?.length?.toString() ?: "-"

    private fun action(a: RvAction): String = when (a.method) {
        "setText" -> "${a.viewName}.setText(len=${a.value.length})"
        "setImageResource" -> "${a.viewName}.setImageResource(${a.value})"
        else -> "${a.viewName}.${a.method}"
    }
}

package com.bydmate.app.ui.widget

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AirlineSeatReclineNormal
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EvStation
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Window
import androidx.compose.ui.graphics.vector.ImageVector
import com.bydmate.app.R

/** One entry of the fixed icon catalog offered for the widget buttons. */
data class ButtonIcon(
    val id: String,
    val vector: ImageVector,
    @StringRes val labelRes: Int,
)

/**
 * Car-themed icons a user can put on an expandable widget button instead of its
 * number. Ids are stable strings persisted in [WidgetPreferences] — never rename
 * one, an unknown id simply falls back to the number.
 */
object WidgetButtonIcons {

    val CATALOG: List<ButtonIcon> = listOf(
        ButtonIcon("window", Icons.Outlined.Window, R.string.widget_button_icon_window),
        ButtonIcon("sunroof", Icons.Outlined.WbSunny, R.string.widget_button_icon_sunroof),
        ButtonIcon("trunk", Icons.Outlined.Inventory2, R.string.widget_button_icon_trunk),
        ButtonIcon("front_trunk", Icons.Outlined.Archive, R.string.widget_button_icon_front_trunk),
        ButtonIcon("lock", Icons.Outlined.Lock, R.string.widget_button_icon_lock),
        ButtonIcon("unlock", Icons.Outlined.LockOpen, R.string.widget_button_icon_unlock),
        ButtonIcon("ac", Icons.Outlined.AcUnit, R.string.widget_button_icon_ac),
        ButtonIcon("fan", Icons.Outlined.Air, R.string.widget_button_icon_fan),
        ButtonIcon("heat", Icons.Outlined.LocalFireDepartment, R.string.widget_button_icon_heat),
        ButtonIcon("seat", Icons.Outlined.AirlineSeatReclineNormal, R.string.widget_button_icon_seat),
        ButtonIcon("light", Icons.Outlined.Lightbulb, R.string.widget_button_icon_light),
        ButtonIcon("mirror", Icons.Outlined.Flip, R.string.widget_button_icon_mirror),
        ButtonIcon("camera", Icons.Outlined.PhotoCamera, R.string.widget_button_icon_camera),
        ButtonIcon("navigation", Icons.Outlined.Navigation, R.string.widget_button_icon_navigation),
        ButtonIcon("cluster", Icons.Outlined.Speed, R.string.widget_button_icon_cluster),
        ButtonIcon("car", Icons.Outlined.DirectionsCar, R.string.widget_button_icon_car),
        ButtonIcon("home", Icons.Outlined.Home, R.string.widget_button_icon_home),
        ButtonIcon("music", Icons.Outlined.MusicNote, R.string.widget_button_icon_music),
        ButtonIcon("phone", Icons.Outlined.Phone, R.string.widget_button_icon_phone),
        ButtonIcon("youtube", Icons.Outlined.SmartDisplay, R.string.widget_button_icon_youtube),
        ButtonIcon("split", Icons.Outlined.VerticalSplit, R.string.widget_button_icon_split),
        ButtonIcon("sentry", Icons.Outlined.Shield, R.string.widget_button_icon_sentry),
        ButtonIcon("wifi", Icons.Outlined.Wifi, R.string.widget_button_icon_wifi),
        ButtonIcon("charge", Icons.Outlined.EvStation, R.string.widget_button_icon_charge),
        ButtonIcon("fridge", Icons.Outlined.Kitchen, R.string.widget_button_icon_fridge),
        ButtonIcon("star", Icons.Outlined.Star, R.string.widget_button_icon_star),
    )

    private val byId: Map<String, ButtonIcon> = CATALOG.associateBy { it.id }

    /** Null for an unknown or absent id — the caller then draws the button number. */
    fun find(id: String?): ButtonIcon? = id?.let { byId[it] }
}

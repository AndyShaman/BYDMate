package com.bydmate.app.hud

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent ring of the maneuvers that actually left the app, printed in the diagnostic dump.
 *
 * On a roundabout the glass draws a left-turn animation (#94) even though both channels look
 * correct in code, so a field log has to say what each channel carried at that moment: the
 * SOME/IP arrow field (f28) and the Amap broadcast icon (NEW_ICON, plus the exit number when
 * one is sent). The push loop fires twice a second, so only a CHANGE of the maneuver code or
 * of the arrow-suppression flag is recorded — a per-frame journal would flush itself in
 * seconds and hammer the prefs file. Each line also says which lane read the maneuver and from
 * what (#294: a right arrow while the navigator showed a left turn).
 */
class HudManeuverJournal(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Records one maneuver transition. [amapIcon] is null when the Amap channel is not
     * broadcasting on this car (no receiver package installed), which is itself the answer to
     * "why did only one channel change". [source] and [raw]: the lane that read the maneuver and
     * the input behind its code, empty while no maneuver is held.
     */
    @Synchronized
    @Suppress("LongParameterList") // the fields of one journal line
    fun append(
        maneuverGaode: Int,
        distanceMeters: Int,
        f28: Int,
        amapIcon: Int?,
        roundaboutNum: Int?,
        suppressArrow: Boolean,
        source: String,
        raw: String,
    ) {
        val ts = SimpleDateFormat(TS_FORMAT, Locale.US).format(Date(now()))
        val amap = when {
            amapIcon == null -> "off"
            roundaboutNum != null -> "$amapIcon rab=$roundaboutNum"
            else -> "$amapIcon"
        }
        val line = "$ts gaode=$maneuverGaode dist=${distanceMeters}m f28=$f28 " +
            "amap=$amap suppress=$suppressArrow src=${source.ifEmpty { "none" }} raw=\"$raw\""
        val lines = (lines() + line).takeLast(MAX_ENTRIES)
        prefs.edit().putString(KEY_JOURNAL, lines.joinToString("\n")).apply()
    }

    /** Ring content, oldest first. */
    fun lines(): List<String> =
        prefs.getString(KEY_JOURNAL, "").orEmpty().lines().filter { it.isNotBlank() }

    companion object {
        const val MAX_ENTRIES = 20
        private const val KEY_JOURNAL = "maneuver_journal"
        private const val TS_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }
}

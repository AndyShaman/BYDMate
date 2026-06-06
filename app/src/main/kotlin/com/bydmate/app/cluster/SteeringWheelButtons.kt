package com.bydmate.app.cluster

import androidx.annotation.StringRes
import com.bydmate.app.R

/**
 * Known steering-wheel keycodes → display name string resource. Display only — it does NOT gate
 * which keys are assignable (that is [isAssignable]). Ours (351/305/309) validated on Leopard 3;
 * the rest (310/320/321/383) are OpenBYD's KNOWN_KEYCODES. Unknown codes fall back to
 * R.string.steering_button_unknown with the raw number.
 */
val KNOWN_BUTTON_NAMES: Map<Int, Int> = mapOf(
    351 to R.string.steering_button_right_star,
    305 to R.string.steering_button_left_star,
    309 to R.string.steering_button_carousel,
    310 to R.string.steering_button_surround_view,
    320 to R.string.steering_button_voice,
    321 to R.string.steering_button_aux_left,
    383 to R.string.steering_button_aux_right,
)

/** @StringRes name for [keyCode], or 0 if unknown (caller falls back to the "unknown" string). */
@StringRes
fun knownButtonNameRes(keyCode: Int): Int = KNOWN_BUTTON_NAMES[keyCode] ?: 0

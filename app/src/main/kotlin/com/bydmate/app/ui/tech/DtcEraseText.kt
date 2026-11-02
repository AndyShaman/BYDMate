package com.bydmate.app.ui.tech

import androidx.compose.ui.graphics.Color
import com.bydmate.app.R
import com.bydmate.app.data.dtc.DtcEraseState
import com.bydmate.app.data.dtc.DtcEraseVerdict
import com.bydmate.app.data.dtc.DtcScanError
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** True for the «Недоступно» texts, which replace «Ещё не сканировали» on the card. */
internal fun dtcUnavailableText(res: Int?): Boolean =
    res == R.string.tech_dtc_unavailable || res == R.string.tech_dtc_no_diag_service

/** One line for an erase that did not run or whose end is unknown. */
internal fun dtcEraseErrorText(error: DtcScanError?): Int? = when (error) {
    null -> null
    DtcScanError.NOT_PARK -> R.string.tech_dtc_only_on_p_erase
    DtcScanError.ERASE_NEEDS_SCAN -> R.string.tech_dtc_erase_needs_scan
    DtcScanError.NO_DIAG_SERVICE -> R.string.tech_dtc_no_diag_service
    DtcScanError.UNAVAILABLE -> R.string.tech_dtc_unavailable
    // The status was lost after the start: a clear may have gone out.
    DtcScanError.STATUS_LOST -> R.string.tech_dtc_erase_unknown
    else -> R.string.tech_dtc_erase_failed
}

internal fun dtcEraseVerdictText(verdict: DtcEraseVerdict): Int = when (verdict) {
    DtcEraseVerdict.ERASED -> R.string.tech_dtc_v_erased
    DtcEraseVerdict.ERASED_BUT_BACK -> R.string.tech_dtc_v_back
    DtcEraseVerdict.LIKELY_ERASED -> R.string.tech_dtc_v_likely
    DtcEraseVerdict.UNKNOWN_CODES_PRESENT -> R.string.tech_dtc_v_unknown_codes
    DtcEraseVerdict.CONFIRMED_UNVERIFIED -> R.string.tech_dtc_v_confirmed_unverified
    DtcEraseVerdict.UNCONFIRMED_UNVERIFIED -> R.string.tech_dtc_v_unconfirmed_unverified
    DtcEraseVerdict.REFUSED -> R.string.tech_dtc_v_refused
    DtcEraseVerdict.NOT_RUN -> R.string.tech_dtc_v_not_run
}

/** «есть сейчас» yellow, everything else grey; never red. */
internal fun dtcEraseVerdictColor(verdict: DtcEraseVerdict): Color =
    if (verdict == DtcEraseVerdict.ERASED_BUT_BACK) SocYellow else TextMuted

/** The line over the results of a stopped erase, from the daemon's stop reason (`not_park gear=4`…). */
internal fun dtcEraseStopText(reason: String): Int = when (reason.substringBefore(' ')) {
    "not_park" -> R.string.tech_dtc_stop_not_park
    "speed" -> R.string.tech_dtc_stop_speed
    "diag_busy", "passive_traffic", "foreign" -> R.string.tech_dtc_stop_busy
    "budget" -> R.string.tech_dtc_stop_budget
    else -> R.string.tech_dtc_stop_other
}

/** The stop reason's key (`not_park`, `foreign`…) of an erase the daemon stopped; null after a normal end. */
internal fun dtcEraseStopReasonKey(erase: DtcEraseState): String? =
    if (erase.error != null) null else erase.reason.substringBefore(' ').ifEmpty { null }

/** The block in the confirmation title, with its addresses unless the block text already holds them. */
internal fun dtcEraseTitleName(blockText: String, addrs: List<Int>): String =
    if (addrs.all { blockText.contains("0x%X".format(it), ignoreCase = true) }) {
        blockText
    } else {
        "$blockText (${addrsText(addrs)})"
    }

/** «16:34», the time of the erase on the card. */
internal fun dtcTime(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

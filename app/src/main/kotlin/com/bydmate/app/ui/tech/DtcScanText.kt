package com.bydmate.app.ui.tech

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.ui.graphics.Color
import com.bydmate.app.R
import com.bydmate.app.data.dtc.DtcBlock
import com.bydmate.app.data.dtc.DtcBlockName
import com.bydmate.app.data.dtc.DtcKind
import com.bydmate.app.data.dtc.DtcLabels
import com.bydmate.app.data.dtc.DtcScanError
import com.bydmate.app.data.dtc.DtcSendOutcome
import com.bydmate.app.data.dtc.DtcSendResult
import com.bydmate.app.ui.settings.telegramErrorText
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Short block name for the card rows. */
internal fun dtcBlockShort(context: Context, name: DtcBlockName): String = when (name.block) {
    DtcBlock.ADAS -> context.getString(R.string.tech_dtc_block_adas)
    DtcBlock.BRAKES -> context.getString(R.string.tech_dtc_block_brakes)
    DtcBlock.STEERING -> context.getString(R.string.tech_dtc_block_steering)
    DtcBlock.AIRBAG -> context.getString(R.string.tech_dtc_block_airbag)
    DtcBlock.RADAR -> context.getString(R.string.tech_dtc_block_radar)
    DtcBlock.BMS -> context.getString(R.string.tech_dtc_block_bms)
    DtcBlock.CHARGER -> context.getString(R.string.tech_dtc_block_charger)
    DtcBlock.URE -> context.getString(R.string.tech_dtc_block_ure)
    DtcBlock.BODY -> context.getString(R.string.tech_dtc_block_body, name.addr)
    DtcBlock.UNKNOWN -> context.getString(R.string.tech_dtc_block_unknown, name.addr)
}

/** Full block name for the dialog and the Telegram text. */
internal fun dtcBlockFull(context: Context, name: DtcBlockName): String = when (name.block) {
    DtcBlock.ADAS -> context.getString(R.string.tech_dtc_full_adas)
    DtcBlock.BRAKES -> context.getString(R.string.tech_dtc_full_brakes)
    DtcBlock.STEERING -> context.getString(R.string.tech_dtc_full_steering)
    DtcBlock.AIRBAG -> context.getString(R.string.tech_dtc_full_airbag)
    DtcBlock.RADAR -> context.getString(R.string.tech_dtc_full_radar)
    DtcBlock.BMS -> context.getString(R.string.tech_dtc_full_bms)
    DtcBlock.CHARGER -> context.getString(R.string.tech_dtc_full_charger)
    DtcBlock.URE -> context.getString(R.string.tech_dtc_full_ure)
    DtcBlock.BODY -> context.getString(R.string.tech_dtc_full_body, name.addr)
    DtcBlock.UNKNOWN -> context.getString(R.string.tech_dtc_full_unknown, name.addr)
}

internal fun dtcKindText(kind: DtcKind): Int = when (kind) {
    DtcKind.NOW -> R.string.tech_dtc_kind_now
    DtcKind.OLD -> R.string.tech_dtc_kind_old
    DtcKind.PENDING -> R.string.tech_dtc_kind_pending
}

/** Only a code present now is yellow; old and pending ones stay grey. Never red. */
internal fun dtcKindColor(kind: DtcKind): Color = if (kind == DtcKind.NOW) SocYellow else TextMuted

/** «сегодня 16:34» or «27.10 16:34». */
internal fun dtcWhen(context: Context, at: Long): String =
    if (DateUtils.isToday(at)) {
        context.getString(R.string.tech_dtc_today, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at)))
    } else {
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(at))
    }

internal fun answeredText(context: Context, answered: Int, total: Int): String =
    context.resources.getQuantityString(R.plurals.tech_dtc_answered, answered, answered, total)

/** The texts of the manual Telegram send, in the app language. */
internal fun dtcLabels(context: Context): DtcLabels = object : DtcLabels {
    override val title = context.getString(R.string.tech_dtc_tg_title)
    override fun checked(at: String, answered: Int, total: Int) =
        context.getString(R.string.tech_dtc_tg_checked, at, answeredText(context, answered, total))
    override val nowHeader = context.getString(R.string.tech_dtc_tg_now)
    override val oldHeader = context.getString(R.string.tech_dtc_tg_old)
    override val pendingMark = context.getString(R.string.tech_dtc_kind_pending)
    override val noErrors = context.getString(R.string.tech_dtc_none_found)
    override val footer = context.getString(R.string.tech_dtc_note)
    override fun blockName(name: DtcBlockName) = dtcBlockFull(context, name)
    override fun unread(count: Int) = context.resources.getQuantityString(R.plurals.tech_dtc_unread, count, count)
    override fun moreCodes(count: Int) = context.resources.getQuantityString(R.plurals.tech_dtc_more_codes, count, count)
    override val keyHint = context.getString(R.string.tech_dtc_key_hint)
    override val similarNote = context.getString(R.string.tech_dtc_similar_note)
    override val zh = dtcZh(context)
}

/** Descriptions follow the app language: the manual's Chinese for Chinese, English for the rest. */
internal fun dtcZh(context: Context): Boolean = context.resources.configuration.locales[0].language == "zh"

/** One short line for an attempt that ended without a result; null when there is nothing to say. */
internal fun dtcErrorText(error: DtcScanError?): Int? = when (error) {
    null -> null
    DtcScanError.NOT_PARK -> R.string.tech_dtc_only_on_p
    DtcScanError.UNAVAILABLE -> R.string.tech_dtc_unavailable
    else -> R.string.tech_dtc_failed
}

internal fun sendResultText(context: Context, result: DtcSendResult?): String? = when (result?.outcome) {
    null -> null
    DtcSendOutcome.SENT -> context.getString(R.string.tech_dtc_tg_sent)
    DtcSendOutcome.NO_BOT -> context.getString(R.string.tech_dtc_tg_no_bot)
    DtcSendOutcome.FAILED ->
        context.getString(R.string.tech_dtc_tg_failed, result.error?.let { telegramErrorText(context, it.name) } ?: "?")
}

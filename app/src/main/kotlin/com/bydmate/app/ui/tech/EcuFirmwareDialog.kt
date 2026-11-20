package com.bydmate.app.ui.tech

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.data.dtc.DtcBlock
import com.bydmate.app.data.dtc.DtcBlockName
import com.bydmate.app.data.dtc.DtcScanError
import com.bydmate.app.data.dtc.EcuFirmwareText
import com.bydmate.app.data.dtc.FirmwareChange
import com.bydmate.app.data.dtc.FirmwareLabels
import com.bydmate.app.data.dtc.FirmwarePhase
import com.bydmate.app.data.dtc.FirmwareRow
import com.bydmate.app.data.dtc.FirmwareSections
import com.bydmate.app.data.dtc.FirmwareUiState
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.ScaledDialogContent
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/**
 * «Прошивки блоков» (CONTEXT.md «Прошивки блоков (2026-11-20)»): the last saved check, the
 * reflashed blocks «было → стало» on top, the rest below. Opening it reads nothing; «Проверить
 * сейчас» has the scan's gates, «Отправить в Telegram» is one plain attempt with a bot bound.
 */
@Composable
internal fun EcuFirmwareDialog(
    state: TechPanelUiState,
    window: FirmwareWindowState,
    onCheck: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val fw = state.firmware
    val checking = fw.phase == FirmwarePhase.CHECKING
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ScaledDialogContent {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.widthIn(max = 980.dp).fillMaxWidth().padding(24.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.tech_fw_row_title), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            subtitle(context, fw)?.let { Text(it, color = TextMuted, fontSize = 11.sp) }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.trip_detail_close), tint = TextSecondary)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                    ) {
                        when {
                            checking -> CheckProgress(fw)
                            fw.huChanged -> Text(stringResource(R.string.tech_fw_hu_note), color = AccentBlue, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        when {
                            checking -> Unit
                            fw.history.isEmpty() -> NoneText(R.string.tech_fw_never)
                            else -> Sections(context, fw.rows)
                        }
                        if (!checking) firmwareErrorText(fw.error)?.let { NoneText(it) }
                    }
                    Footer(state, window, onCheck, onSend)
                }
            }
        }
    }
}

/** «последняя проверка 20.11 10:55», or with a check before it «27.11 09:10 · прошлая проверка 20.11 10:55». */
private fun subtitle(context: Context, fw: FirmwareUiState): String? {
    val last = fw.history.lastOrNull() ?: return null
    val previous = fw.history.getOrNull(fw.history.size - 2)
    return if (previous == null) {
        context.getString(R.string.tech_fw_sub_last, dtcWhen(context, last.at))
    } else {
        context.getString(R.string.tech_fw_sub_previous, dtcWhen(context, last.at), dtcWhen(context, previous.at))
    }
}

@Composable
private fun CheckProgress(fw: FirmwareUiState) {
    Text(stringResource(R.string.tech_fw_progress, fw.done, fw.total), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
    if (fw.total > 0) {
        LinearProgressIndicator(
            progress = { (fw.done.toFloat() / fw.total).coerceIn(0f, 1f) },
            color = AccentGreen,
            trackColor = CardBorder,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
    NoneText(R.string.tech_fw_progress_note)
}

@Composable
private fun Sections(context: Context, rows: List<FirmwareRow>) {
    val s = FirmwareSections(rows)
    val labels = firmwareLabels(context)
    for ((header, list) in listOf(labels.updated(s.updated.size) to s.updated, labels.unchanged(s.unchanged.size) to s.unchanged, labels.first(s.first.size) to s.first)) {
        if (list.isEmpty()) continue
        Text(header, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        list.forEach { BlockRow(it, labels.blockName(it.name)) }
    }
    if (s.silent.isNotEmpty()) {
        Text(
            labels.silent(s.silent.size) + ": " + s.silent.joinToString(", ") { EcuFirmwareText.addr(it.addr) },
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** `0x720  Кузовной блок 0x720  4.00.10 → 4.00.12 · 10.09.2026`; an unnamed block shows its address alone. */
@Composable
private fun BlockRow(row: FirmwareRow, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(EcuFirmwareText.addr(row.addr), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(60.dp))
        Text(name, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(
            EcuFirmwareText.versionLine(row),
            color = if (row.change == FirmwareChange.UPDATED) AccentBlue else TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Footer(state: TechPanelUiState, window: FirmwareWindowState, onCheck: () -> Unit, onSend: () -> Unit) {
    val context = LocalContext.current
    val checking = state.firmware.phase == FirmwarePhase.CHECKING
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DialogButton(
            text = stringResource(if (checking) R.string.tech_fw_checking else R.string.tech_fw_check),
            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(20.dp)) },
            enabled = state.canCheckFirmware,
            accent = true,
            onClick = onCheck,
        )
        if (window.telegramReady && state.firmware.history.isNotEmpty()) {
            DialogButton(
                text = stringResource(if (window.sending) R.string.tech_dtc_tg_sending else R.string.tech_dtc_send_tg),
                icon = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(20.dp)) },
                enabled = !window.sending,
                accent = false,
                onClick = onSend,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            if (!checking && state.scanLabel == DtcScanLabel.ONLY_PARK) {
                Text(stringResource(R.string.tech_fw_only_on_p), color = TextMuted, fontSize = 11.sp)
            }
            sendResultText(context, window.sendResult)?.let { Text(it, color = TextSecondary, fontSize = 11.sp) }
        }
    }
}

/** One short line for a check that ended without a result; null when there is nothing to say. */
private fun firmwareErrorText(error: DtcScanError?): Int? =
    if (error == DtcScanError.NOT_PARK) R.string.tech_fw_only_on_p else dtcErrorText(error)

/** The firmware texts in the app language; an unnamed block has no name, its address says it. */
internal fun firmwareLabels(context: Context): FirmwareLabels = object : FirmwareLabels {
    override val title = context.getString(R.string.tech_fw_tg_title)
    override fun checked(at: String) = context.getString(R.string.tech_fw_tg_checked, at)
    override fun previous(at: String) = context.getString(R.string.tech_fw_tg_previous, at)
    override fun updated(count: Int) = context.getString(R.string.tech_fw_updated, count)
    override fun unchanged(count: Int) = context.getString(R.string.tech_fw_unchanged, count)
    override fun first(count: Int) = context.getString(R.string.tech_fw_first, count)
    override fun silent(count: Int) = context.getString(R.string.tech_fw_silent, count)
    override fun more(count: Int) = context.getString(R.string.tech_fw_more, count)
    override fun blockName(name: DtcBlockName) = if (name.block == DtcBlock.UNKNOWN) "" else dtcBlockFull(context, name)
}

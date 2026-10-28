package com.bydmate.app.ui.tech

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.bydmate.app.data.dtc.DtcFound
import com.bydmate.app.data.dtc.DtcKind
import com.bydmate.app.data.dtc.DtcParser
import com.bydmate.app.data.dtc.DtcScanPhase
import com.bydmate.app.data.dtc.DtcScanState
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyMid
import com.bydmate.app.ui.theme.ScaledDialogContent
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/** Progress of a running scan, as the daemon counts the addresses. */
@Composable
internal fun DtcProgress(scan: DtcScanState) {
    Text(
        stringResource(R.string.tech_dtc_progress, scan.done, scan.total),
        color = TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    if (scan.total > 0) {
        LinearProgressIndicator(
            progress = { (scan.done.toFloat() / scan.total).coerceIn(0f, 1f) },
            color = AccentGreen,
            trackColor = CardBorder,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

/**
 * «Диагностика · ошибки в блоках»: the saved scan in full. Opening it never scans; «Сканировать
 * снова» has the card button's gates, «Отправить в Telegram» is one plain attempt and shows only
 * with a bot bound. No red, no clear button.
 */
@Composable
internal fun DtcScanDialog(
    state: TechPanelUiState,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val summary = state.dtcSummary
    val scanning = state.dtcScan.phase == DtcScanPhase.SCANNING
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
                            Text(
                                stringResource(R.string.tech_dtc_dialog_title),
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (summary != null && state.dtcCheckedAt != null) {
                                Text(
                                    stringResource(
                                        R.string.tech_dtc_checked_dialog,
                                        dtcWhen(context, state.dtcCheckedAt),
                                        answeredText(context, summary.answered, summary.total),
                                    ),
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.trip_detail_close),
                                tint = TextSecondary,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                    ) {
                        when {
                            scanning -> DtcProgress(state.dtcScan)
                            summary == null -> Unit
                            summary.found.isEmpty() -> NoneText(dtcNoRowsText(context, summary))
                            else -> {
                                val now = summary.found.filter { it.row.kind == DtcKind.NOW }
                                val old = summary.found.filter { it.row.kind != DtcKind.NOW }
                                DialogSection(R.string.tech_dtc_now_header, R.string.tech_dtc_now_sub, now)
                                DialogSection(R.string.tech_dtc_old_header, R.string.tech_dtc_old_sub, old)
                            }
                        }
                        if (!scanning) dtcErrorText(state.dtcScan.error)?.let { NoneText(it) }
                    }
                    DialogFooter(state, onScan, onSend)
                }
            }
        }
    }
}

@Composable
private fun DialogSection(header: Int, sub: Int, rows: List<DtcFound>) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
        Text(stringResource(header), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(sub), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
    }
    if (rows.isEmpty()) NoneText(R.string.tech_value_none)
    rows.forEach { CodeRow(it) }
}

@Composable
private fun CodeRow(found: DtcFound) {
    val context = LocalContext.current
    val kind = found.row.kind
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(NavyMid, shape)
            .border(1.dp, if (kind == DtcKind.NOW) SocYellow else CardBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                found.row.code,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                dtcBlockFull(context, found.block),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            )
            Text(stringResource(dtcKindText(kind)), color = dtcKindColor(kind), fontSize = 11.sp)
        }
        DtcParser.faultType(found.row.ftb)?.let {
            Text(stringResource(faultText(it)), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun NoneText(res: Int) = NoneText(stringResource(res))

@Composable
private fun NoneText(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp))
}

@Composable
private fun DialogFooter(state: TechPanelUiState, onScan: () -> Unit, onSend: () -> Unit) {
    val context = LocalContext.current
    val scanning = state.dtcScan.phase == DtcScanPhase.SCANNING
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DialogButton(
            text = stringResource(if (scanning) R.string.tech_dtc_scanning else R.string.tech_dtc_rescan),
            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(20.dp)) },
            enabled = state.canScan,
            accent = true,
            onClick = onScan,
        )
        if (state.dtcTelegramReady && state.dtcSummary != null) {
            DialogButton(
                text = stringResource(if (state.dtcSending) R.string.tech_dtc_tg_sending else R.string.tech_dtc_send_tg),
                icon = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(20.dp)) },
                enabled = !state.dtcSending,
                accent = false,
                onClick = onSend,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            if (!scanning && state.scanLabel == DtcScanLabel.ONLY_PARK) {
                Text(stringResource(R.string.tech_dtc_only_on_p), color = TextMuted, fontSize = 11.sp)
            }
            sendResultText(context, state.dtcSendResult)?.let { Text(it, color = TextSecondary, fontSize = 11.sp) }
            Text(stringResource(R.string.tech_dtc_note), color = TextMuted, fontSize = 11.sp)
        }
    }
}

/** Outlined, at least 48 dp high: the scan action green, the rest neutral, as in the mock. */
@Composable
internal fun DialogButton(
    text: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (accent) AccentGreen else TextPrimary
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (accent) color.copy(alpha = if (enabled) 1f else DISABLED_ALPHA) else CardBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = CardSurfaceElevated,
            contentColor = color,
            disabledContainerColor = CardSurfaceElevated,
            disabledContentColor = color.copy(alpha = DISABLED_ALPHA),
        ),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        icon()
        Box(modifier = Modifier.padding(start = 8.dp)) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

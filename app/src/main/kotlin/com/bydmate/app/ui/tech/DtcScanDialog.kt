package com.bydmate.app.ui.tech

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.data.dtc.DtcDescLevel
import com.bydmate.app.data.dtc.DtcDescription
import com.bydmate.app.data.dtc.DtcFound
import com.bydmate.app.data.dtc.DtcKind
import com.bydmate.app.data.dtc.DtcParser
import com.bydmate.app.data.dtc.DtcScanPhase
import com.bydmate.app.data.dtc.DtcScanState
import com.bydmate.app.data.dtc.DtcTelegramText
import com.bydmate.app.ui.settings.DonateDialog
import com.bydmate.app.ui.settings.DonateEntry
import com.bydmate.app.ui.theme.AccentBlue
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
 * with a bot bound. «Стереть все» and «стереть ›» on a block have the scan's gate and always ask
 * first. No red.
 */
@Composable
internal fun DtcScanDialog(
    state: TechPanelUiState,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onErase: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val summary = state.dtcSummary
    val scanning = state.dtcScan.phase == DtcScanPhase.SCANNING
    var showDonate by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<EraseTarget?>(null) }
    val onEraseBlock: ((EraseTarget.Block) -> Unit)? =
        if (state.showErase) ({ confirm = it }) else null
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
                                        answeredText(context, summary.answered),
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
                        val erase = state.dtcErase
                        when {
                            scanning -> DtcProgress(state.dtcScan)
                            erase != null && state.dtcErasing -> DtcEraseProgress(erase)
                            erase != null -> EraseResults(erase, state.dtcBlocks)
                        }
                        when {
                            scanning -> Unit
                            summary == null -> Unit
                            summary.found.isEmpty() -> NoneText(dtcNoRowsText(context, summary))
                            else -> {
                                // A merged group lists the rows of its first address once, under all its addresses.
                                val groups = DtcParser.groups(summary.found).associate { it.addrs.first() to it.addrs }
                                val found = summary.found.filter { it.addr in groups }
                                val now = found.filter { it.row.kind == DtcKind.NOW }
                                val old = found.filter { it.row.kind != DtcKind.NOW }
                                val zh = dtcZh(context)
                                val section = SectionData(groups, state.dtcDescriptions, zh, state.canScan, onEraseBlock, state::canEraseBlock)
                                DialogSection(R.string.tech_dtc_now_header, R.string.tech_dtc_now_sub, now, section)
                                DialogSection(R.string.tech_dtc_old_header, R.string.tech_dtc_old_sub, old, section)
                                dtcUnreadPartialText(context, summary)?.let { NoneText(it) }
                            }
                        }
                        if (!scanning) dtcErrorText(state.dtcScan.error)?.let { NoneText(it) }
                        if (!scanning && state.dtcScan.firmwareHint) NoneText(R.string.tech_dtc_firmware_hint)
                        DtcSupportLine(onSupport = { showDonate = true })
                    }
                    DialogFooter(state, onScan, onSend, onEraseAll = { confirm = EraseTarget.All })
                }
            }
        }
    }
    // Over the DTC dialog; the deliberate entry never touches the app-wide opt-out flag.
    if (showDonate) DonateDialog(entry = DonateEntry.SETTINGS, onDismiss = { showDonate = false })
    confirm?.let { target ->
        EraseConfirm(
            target = target,
            enabled = state.canScan,
            onConfirm = {
                confirm = null
                onErase((target as? EraseTarget.Block)?.addrs?.first())
            },
            onDismiss = { confirm = null },
        )
    }
}

/** What every block box of the dialog shares. */
private class SectionData(
    val groups: Map<Int, List<Int>>,
    val descriptions: Map<DtcFound, DtcDescription>,
    val zh: Boolean,
    val canErase: Boolean,
    val onErase: ((EraseTarget.Block) -> Unit)?,
    /** False for a block of unknown erase result: it shows no «стереть ›» until a new scan. */
    val erasable: (Int) -> Boolean,
)

@Composable
private fun DialogSection(header: Int, sub: Int, rows: List<DtcFound>, section: SectionData) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
        Text(stringResource(header), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(sub), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
    }
    if (rows.isEmpty()) NoneText(R.string.tech_value_none)
    rows.groupBy { it.addr }.values.forEach { BlockBox(it, section) }
}

/**
 * One block: `address  full name  …  N кодов  стереть ›`, yellow border when it holds a code
 * present now; a merged group names all its addresses.
 */
@Composable
private fun BlockBox(codes: List<DtcFound>, section: SectionData) {
    val context = LocalContext.current
    val block = codes.first().block
    val addrs = section.groups[block.addr] ?: listOf(block.addr)
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(NavyMid, shape)
            .border(1.dp, if (codes.any { it.row.kind == DtcKind.NOW }) SocYellow else CardBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(addrsText(addrs), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                dtcBlockFull(context, block),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(
                context.resources.getQuantityString(R.plurals.tech_dtc_codes, codes.size, codes.size),
                color = TextMuted,
                fontSize = 11.sp,
            )
            section.onErase?.takeIf { section.erasable(block.addr) }?.let { onErase ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = section.canErase) { onErase(EraseTarget.Block(addrs, block)) }
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.tech_dtc_erase_block),
                        color = TextSecondary.copy(alpha = if (section.canErase) 1f else DISABLED_ALPHA),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        codes.forEach { CodeRow(it, section.descriptions[it], section.zh) }
    }
}

/** Code | description with its source line (or «no description») | status; the key hint under U1166-87 of ADAS. */
@Composable
private fun CodeRow(found: DtcFound, description: DtcDescription?, zh: Boolean) {
    val kind = found.row.kind
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                found.row.code,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                val text = description?.let { if (zh) it.zh else it.en }?.takeIf { it.isNotBlank() }
                if (text == null) {
                    Text(stringResource(R.string.tech_dtc_no_desc), color = TextMuted, fontSize = 13.sp)
                } else {
                    Text(text, color = TextPrimary, fontSize = 13.sp)
                    Text(
                        stringResource(
                            if (description.level == DtcDescLevel.EXACT) R.string.tech_dtc_src_exact else R.string.tech_dtc_src_similar,
                        ),
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
            Text(stringResource(dtcKindText(kind)), color = dtcKindColor(kind), fontSize = 11.sp)
        }
        if (DtcTelegramText.isKeyCode(found)) KeyHint()
    }
}

/** The U1166-87 hint: a box with a blue bar on the left, in the UI language. */
@Composable
private fun KeyHint() {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(HintSurface),
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(AccentBlue))
        Text(
            stringResource(R.string.tech_dtc_key_hint),
            color = TextPrimary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        )
    }
}

@Composable
internal fun NoneText(res: Int) = NoneText(stringResource(res))

@Composable
internal fun NoneText(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp))
}

@Composable
private fun DialogFooter(state: TechPanelUiState, onScan: () -> Unit, onSend: () -> Unit, onEraseAll: () -> Unit) {
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
        if (state.showErase) {
            DialogButton(
                text = stringResource(R.string.tech_dtc_erase_all),
                icon = { Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                enabled = state.canScan,
                accent = false,
                onClick = onEraseAll,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            if (!scanning && state.scanLabel == DtcScanLabel.ONLY_PARK) {
                Text(
                    stringResource(if (state.showErase) R.string.tech_dtc_only_on_p_erase else R.string.tech_dtc_only_on_p),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
            sendResultText(context, state.dtcSendResult)?.let { Text(it, color = TextSecondary, fontSize = 11.sp) }
            Text(
                stringResource(if (state.showErase) R.string.tech_dtc_note_erase else R.string.tech_dtc_note),
                color = TextMuted,
                fontSize = 11.sp,
            )
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

/** Background of the key hint box, as in the approved mock. */
private val HintSurface = Color(0xFF15263A)

package com.bydmate.app.ui.tech

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.dtc.DtcKind
import com.bydmate.app.data.dtc.DtcParser
import com.bydmate.app.data.dtc.DtcScanPhase
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/**
 * «Диагностика»: what the car itself reports (cluster warnings, service countdown), then the
 * block faults of the last manual scan. No red here: a warning reads yellow, «нет» green.
 */
@Composable
internal fun DiagnosticsCard(
    state: TechPanelUiState,
    onHint: (String) -> Unit,
    onScan: () -> Unit,
    onOpenDtc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TechCard(
        stringResource(R.string.tech_card_diagnostics),
        hintKey = "diagnostics",
        onHint = onHint,
        modifier = modifier,
    ) {
        // Fills what the row's tallest card leaves, so the scan button sits at the bottom.
        Column(modifier = Modifier.fillMaxHeight()) {
            state.faultCount?.let { n ->
                TechRow(
                    stringResource(R.string.tech_label_dash_warnings),
                    if (n == 0) stringResource(R.string.tech_value_none) else n.toString(),
                    valueColor = if (n == 0) AccentGreen else SocYellow,
                )
            }
            if (state.hasServiceRow) {
                val days = state.serviceDaysLeft
                val km = state.serviceKmLeft
                TechRow(
                    stringResource(R.string.tech_label_service),
                    listOfNotNull(
                        days?.let { stringResource(R.string.tech_value_service_days, it) },
                        km?.let { stringResource(R.string.tech_value_service_km, it) },
                    ).joinToString(" / "),
                    valueColor = if (days == 0 || km == 0) SocYellow else TextPrimary,
                )
            }
            Hint(state.openHint, "diagnostics", R.string.tech_hint_diagnostics)
            DtcScanBlock(state, onScan, onOpenDtc)
        }
    }
}

/**
 * «Ошибки в блоках» of the last manual scan: one line per block (address, short name, its first
 * two codes), up to three blocks, the rest in the dialog. The scan runs only on a tap, only on P;
 * a failed attempt keeps the last good scan and adds one line.
 */
@Composable
private fun ColumnScope.DtcScanBlock(state: TechPanelUiState, onScan: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val summary = state.dtcSummary
    val scanning = state.dtcScan.phase == DtcScanPhase.SCANNING
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        SectionHeader(stringResource(R.string.tech_section_dtc))
        Box(modifier = Modifier.weight(1f))
        val at = state.dtcCheckedAt
        if (summary != null && at != null && !scanning) {
            Text(stringResource(R.string.tech_dtc_checked_card, dtcWhen(context, at)), color = TextMuted, fontSize = 10.sp)
        }
    }
    val error = dtcErrorText(state.dtcScan.error)
        // The caption over the disabled button already says it.
        ?.takeUnless { it == R.string.tech_dtc_only_on_p && state.scanLabel == DtcScanLabel.ONLY_PARK }
    // Before any good scan, «Недоступно» replaces «Ещё не сканировали» instead of adding to it.
    val unavailableOnly = summary == null && dtcUnavailableText(error)
    val erasedAt = state.dtcErasedAt
    when {
        scanning -> DtcProgress(state.dtcScan)
        unavailableOnly -> error?.let { DtcNote(it) }
        summary == null -> DtcNote(R.string.tech_dtc_never)
        // The saved codes may be gone: one line instead of them, until a new scan.
        state.dtcErasedSinceScan && erasedAt != null -> Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpen).padding(vertical = 2.dp),
        ) {
            DtcNote(stringResource(R.string.tech_dtc_erased_at, dtcTime(erasedAt)))
        }
        else -> Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpen).padding(vertical = 2.dp),
        ) {
            if (summary.found.isEmpty()) DtcNote(dtcNoRowsText(context, summary))
            // A merged group shows once, with all its addresses.
            val groups = DtcParser.groups(summary.found).associate { it.addrs.first() to it.addrs }
            val blocks = summary.found.filter { it.addr in groups }.groupBy { it.addr }.values.toList()
            blocks.take(DTC_CARD_BLOCKS).forEach { codes ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        groups[codes.first().addr].orEmpty().joinToString(", ") { "0x%X".format(it) },
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        dtcBlockShort(context, codes.first().block),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                    // Each code in its own color: yellow = now, gray = old.
                    Text(
                        buildAnnotatedString {
                            codes.take(DTC_CARD_CODES).forEachIndexed { i, f ->
                                if (i > 0) append(", ")
                                withStyle(SpanStyle(color = if (f.row.kind == DtcKind.NOW) SocYellow else TextMuted)) {
                                    append(f.row.code)
                                }
                            }
                            if (codes.size > DTC_CARD_CODES) append(" +${codes.size - DTC_CARD_CODES}")
                        },
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
            val more = blocks.size - DTC_CARD_BLOCKS
            if (more > 0) {
                Text(stringResource(R.string.tech_dtc_more, more), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    if (!scanning && error != null && !unavailableOnly) DtcNote(error)
    if (!scanning && state.dtcScan.firmwareHint) DtcNote(R.string.tech_dtc_firmware_hint)
    Box(modifier = Modifier.weight(1f))
    if (state.scanLabel == DtcScanLabel.ONLY_PARK) {
        Text(
            stringResource(R.string.tech_dtc_only_on_p),
            color = TextMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
    DialogButton(
        text = stringResource(if (scanning) R.string.tech_dtc_scanning else R.string.tech_dtc_scan),
        icon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        enabled = state.canScan,
        accent = true,
        onClick = onScan,
        modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
    )
}

@Composable
private fun DtcNote(res: Int) = DtcNote(stringResource(res))

@Composable
private fun DtcNote(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
}

/** Blocks the card lists before «+N ещё», and codes per block before «+N». */
private const val DTC_CARD_BLOCKS = 3
private const val DTC_CARD_CODES = 2

/** How the disabled scan button fades, as in the approved mock. */
internal const val DISABLED_ALPHA = 0.45f

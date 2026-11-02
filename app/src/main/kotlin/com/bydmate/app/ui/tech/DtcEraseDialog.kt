package com.bydmate.app.ui.tech

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.dtc.DtcBlock
import com.bydmate.app.data.dtc.DtcBlockName
import com.bydmate.app.data.dtc.DtcEraseState
import com.bydmate.app.data.dtc.dtcEraseVerdict
import com.bydmate.app.ui.components.AppAlertDialog
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/** Progress of a running erase, as the daemon counts the requested blocks. */
@Composable
internal fun DtcEraseProgress(erase: DtcEraseState) {
    Text(
        stringResource(R.string.tech_dtc_erasing, erase.done, erase.total),
        color = TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    if (erase.total > 0) {
        LinearProgressIndicator(
            progress = { (erase.done.toFloat() / erase.total).coerceIn(0f, 1f) },
            color = AccentGreen,
            trackColor = CardBorder,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

/** What a confirmation would erase: every block with codes, or one block group. */
internal sealed interface EraseTarget {
    data object All : EraseTarget
    data class Block(val addrs: List<Int>, val name: DtcBlockName) : EraseTarget
}

/**
 * The confirmation before any erase: what it does, then the yellow risk line (CONTEXT.md «Ошибки в
 * блоках»). «Стереть» keeps the scan's gate.
 */
@Composable
internal fun EraseConfirm(target: EraseTarget, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                when (target) {
                    EraseTarget.All -> stringResource(R.string.tech_dtc_erase_title_all)
                    is EraseTarget.Block -> stringResource(
                        R.string.tech_dtc_erase_title_block,
                        dtcEraseTitleName(dtcBlockFull(context, target.name), target.addrs),
                    )
                },
                color = TextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        if (target is EraseTarget.Block) R.string.tech_dtc_erase_text_block else R.string.tech_dtc_erase_text_all,
                    ),
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                )
                Text(
                    stringResource(R.string.tech_dtc_erase_warning),
                    color = SocYellow,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.tech_dtc_erase_confirm),
                icon = { Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                enabled = enabled,
                accent = true,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            DialogButton(
                text = stringResource(R.string.settings_cancel_button),
                icon = { Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(20.dp)) },
                enabled = true,
                accent = false,
                onClick = onDismiss,
            )
        },
    )
}

/**
 * The last erase: the stop reason when the daemon stopped it, then one line per block
 * (address, short name, verdict), or one line when the erase did not run.
 */
@Composable
internal fun EraseResults(erase: DtcEraseState, names: Map<Int, DtcBlockName>) {
    val context = LocalContext.current
    dtcEraseErrorText(erase.error)?.let { NoneText(it) }
    if (erase.results.isEmpty()) return
    val verdicts = erase.results.map { it to dtcEraseVerdict(it) }
    dtcEraseStopReasonKey(erase)?.let { NoneText(dtcEraseStopText(it)) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        verdicts.forEach { (r, verdict) ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                Text("0x%X".format(r.addr), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(
                    dtcBlockShort(context, names[r.addr] ?: DtcBlockName(DtcBlock.UNKNOWN, r.addr)),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                Text(stringResource(dtcEraseVerdictText(verdict)), color = dtcEraseVerdictColor(verdict), fontSize = 12.sp)
            }
        }
    }
}

internal fun addrsText(addrs: List<Int>): String = addrs.joinToString(", ") { "0x%X".format(it) }

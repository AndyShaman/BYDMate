package com.bydmate.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.autoservice.AdbVerdict
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/** Short verdict line for the Dashboard header and the Settings ADB card. Empty for [AdbVerdict.OK]. */
@Composable
internal fun adbVerdictText(v: AdbVerdict): String = when (v) {
    AdbVerdict.NOT_ENABLED -> stringResource(R.string.adb_verdict_not_enabled)
    AdbVerdict.OFF_AFTER_REBOOT -> stringResource(R.string.adb_verdict_off_after_reboot)
    AdbVerdict.NO_ACCESS -> stringResource(R.string.adb_verdict_no_access)
    AdbVerdict.HELPER_DOWN -> stringResource(R.string.adb_verdict_helper_down)
    AdbVerdict.OK -> ""
}

/** «ADB не включён» is red; the other verdicts are yellow like the other header lines. */
internal fun adbVerdictColor(v: AdbVerdict): Color = if (v == AdbVerdict.NOT_ENABLED) SocRed else SocYellow

/**
 * Explanation + one action for a verdict, opened by a tap on the verdict line.
 * [onOpenDiagnostics] null hides the primary button of [AdbVerdict.HELPER_DOWN] (already in Settings).
 */
@Composable
internal fun AdbVerdictDialog(
    verdict: AdbVerdict,
    restoreEnabled: Boolean,
    onCheck: () -> Unit,
    onEnableRestore: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    if (verdict == AdbVerdict.OK) return
    val (titleRes, bodyRes) = when (verdict) {
        AdbVerdict.NOT_ENABLED -> R.string.adb_dialog_not_enabled_title to R.string.adb_dialog_not_enabled_body
        AdbVerdict.OFF_AFTER_REBOOT ->
            R.string.adb_dialog_off_after_reboot_title to R.string.adb_dialog_off_after_reboot_body
        AdbVerdict.NO_ACCESS -> R.string.adb_dialog_no_access_title to R.string.adb_dialog_no_access_body
        else -> R.string.adb_dialog_helper_down_title to R.string.adb_dialog_helper_down_body
    }
    // Primary button: label + action, or null when there is nothing to offer.
    val primary: Pair<Int, () -> Unit>? = when (verdict) {
        AdbVerdict.NOT_ENABLED, AdbVerdict.NO_ACCESS -> R.string.adb_dialog_check to onCheck
        AdbVerdict.OFF_AFTER_REBOOT ->
            if (restoreEnabled) R.string.adb_dialog_check to onCheck
            else R.string.adb_dialog_enable_restore to onEnableRestore
        else -> onOpenDiagnostics?.let { R.string.adb_dialog_open_diagnostics to it }
    }
    CardDetailDialog(
        title = null,
        borderColor = adbVerdictColor(verdict),
        onDismiss = onDismiss,
        dismissOnCardTap = false,
    ) {
        Text(stringResource(titleRes), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(bodyRes), color = TextSecondary, fontSize = 13.sp)
        if (verdict == AdbVerdict.NOT_ENABLED) {
            AdbEnableSteps()
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, CardBorder),
            ) {
                Text(stringResource(R.string.adb_dialog_close), color = TextSecondary, fontSize = 13.sp)
            }
            primary?.let { (labelRes, action) ->
                Button(
                    onClick = { action(); onDismiss() },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                ) {
                    Text(stringResource(labelRes), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** The three «how to enable ADB» steps, shared by the dialog and the welcome wizard. */
@Composable
internal fun AdbEnableSteps() {
    listOf(R.string.adb_enable_step_1, R.string.adb_enable_step_2, R.string.adb_enable_step_3).forEach {
        Text(stringResource(it), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

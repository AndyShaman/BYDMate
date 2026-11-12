package com.bydmate.app.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.ui.theme.*

// --- Journal Dialog ---

/**
 * The journal: all rules (header «Журнал», each entry names its rule on top) or one rule
 * ([ruleName] set, opened from the card's status pill).
 */
@Composable
internal fun JournalDialog(logs: List<RuleLogEntity>, ruleName: String?, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScaledDialogContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .fillMaxHeight(0.85f)
                    .background(NavyDeep, RoundedCornerShape(16.dp))
                    .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (ruleName != null) stringResource(R.string.auto_ui_journal_rule_title, ruleName)
                        else stringResource(R.string.automation_journal_title),
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.automation_cancel_button), tint = TextSecondary)
                    }
                }
                HorizontalDivider(color = CardBorder)

                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.automation_journal_empty), color = TextSecondary, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(logs, key = { it.id }) { log ->
                            LogItem(log, showRuleName = ruleName == null)
                        }
                    }
                }
            }
        }
    }
}

/** One entry: verdict and time, what ran, why; the stripe and icon in the verdict's colour. */
@Composable
private fun LogItem(log: RuleLogEntity, showRuleName: Boolean) {
    val context = LocalContext.current
    val today = rememberToday()
    val line = remember(log, today, context) { journalLine(log, context) }
    val color = line.kind.color()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(CardSurface)
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Row(Modifier.weight(1f).padding(12.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(line.kind.icon(), null, tint = color, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                if (showRuleName) {
                    Text(log.ruleName, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.status, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = TextPrimary, modifier = Modifier.weight(1f),
                    )
                    Text(line.time, fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(start = 12.dp))
                }
                if (line.what.isNotEmpty()) Text(line.what, fontSize = 16.sp, lineHeight = 22.sp, color = TextPrimary)
                line.why?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary) }
            }
        }
    }
}

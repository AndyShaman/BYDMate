package com.bydmate.app.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.voice.VoiceJournal
import com.bydmate.app.voice.VoiceJournalEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private val JournalOkGreen = Color(0xFF2E7D32)
private val JournalBrokenRed = Color(0xFFC62828)

@HiltViewModel
class VoiceJournalViewModel @Inject constructor(
    private val journal: VoiceJournal,
) : ViewModel() {
    val entries: StateFlow<List<VoiceJournalEntry>> = journal.entries
    fun clear() = journal.clear()
}

/** Debug-only screen (RU hardcoded, like AgentChatScreen): the last
 *  MAX voice sessions from VoiceJournal — what ASR heard, how it routed, and the outcome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceJournalScreen(
    onBack: () -> Unit,
    viewModel: VoiceJournalViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал голоса") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.clear() }) {
                        Text("Очистить")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(entries) { entry ->
                VoiceJournalRow(entry, timeFormat)
            }
        }
    }
}

@Composable
private fun VoiceJournalRow(entry: VoiceJournalEntry, timeFormat: SimpleDateFormat) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(timeFormat.format(Date(entry.timestampMs)), fontSize = 12.sp, color = Color(0xFF9E9E9E))
        Text(
            text = when (entry.route) {
                VoiceJournalEntry.Route.NLU -> "Команда"
                VoiceJournalEntry.Route.AGENT -> "Агент"
                VoiceJournalEntry.Route.NONE -> "—"
            },
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.transcript, fontSize = 14.sp)
            entry.reason?.let { Text(it, fontSize = 12.sp, color = JournalBrokenRed) }
        }
        Text(
            text = if (entry.outcome == VoiceJournalEntry.Outcome.OK) "✓" else "✗",
            color = if (entry.outcome == VoiceJournalEntry.Outcome.OK) JournalOkGreen else JournalBrokenRed,
            fontSize = 16.sp, fontWeight = FontWeight.Bold,
        )
    }
}

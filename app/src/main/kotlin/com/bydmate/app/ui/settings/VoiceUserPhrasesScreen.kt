package com.bydmate.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.ui.components.AppAlertDialog
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import com.bydmate.app.voice.VoiceUserCommand
import com.bydmate.app.voice.VoiceUserPhrases
import kotlinx.coroutines.launch

/** «Свои фразы для команд»: every built-in offline command with the user's extra phrases,
 *  add (dialog with one text field) and remove per phrase. */
@Composable
fun VoiceUserPhrasesScreen(
    onBack: () -> Unit,
    viewModel: VoiceUserPhrasesViewModel = hiltViewModel(),
) {
    val phrases by viewModel.phrases.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<VoiceUserCommand?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(1.5.dp, TextMuted, CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = TextSecondary, fontSize = 16.sp)
            }
            Text(
                stringResource(R.string.settings_voice_user_phrases_title),
                color = AccentGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp)
            )
        }
        Text(
            stringResource(R.string.voice_user_phrases_hint),
            color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(VoiceUserPhrases.COMMANDS, key = { it.id }) { cmd ->
                CommandPhrasesRow(
                    command = cmd,
                    phrases = phrases[cmd.id].orEmpty(),
                    onAdd = { adding = cmd },
                    onRemove = { viewModel.remove(cmd.id, it) },
                )
            }
        }
    }

    adding?.let { cmd ->
        AddPhraseDialog(
            command = cmd,
            onAdd = { viewModel.add(cmd.id, it) },
            onDismiss = { adding = null },
        )
    }
}

@Composable
private fun CommandPhrasesRow(
    command: VoiceUserCommand,
    phrases: List<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    command.name,
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (phrases.size < VoiceUserPhrases.MAX_PHRASES) {
                    TextButton(onClick = onAdd) {
                        Text(stringResource(R.string.voice_user_phrases_add), color = AccentGreen)
                    }
                }
            }
            phrases.forEach { phrase ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "«$phrase»",
                        color = TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    IconButton(onClick = { onRemove(phrase) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.places_delete_content_description),
                            tint = TextMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPhraseDialog(
    command: VoiceUserCommand,
    onAdd: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceElevated,
        title = { Text(command.name, color = TextPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(VoiceUserPhrases.MAX_CHARS); error = null },
                    label = { Text(stringResource(R.string.voice_user_phrases_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedLabelColor = AccentGreen,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentGreen,
                    ),
                )
                error?.let {
                    Text(it, color = AccentOrange, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val refusal = onAdd(text)
                    if (refusal == null) onDismiss() else error = refusal
                }
            }) {
                Text(stringResource(R.string.automation_save_button), color = AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
            }
        },
    )
}

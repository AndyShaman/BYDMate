package com.bydmate.app.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.ui.theme.*

/** Debug-only screen (RU hardcoded). */
@Composable
fun AgentChatScreen(
    onBack: () -> Unit,
    viewModel: AgentChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад", color = TextSecondary) }
            Spacer(Modifier.width(8.dp))
            Text("Чат с агентом (debug)", color = TextPrimary, fontSize = 16.sp)
            if (state.busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentGreen)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.lines) { line ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (line.fromUser) Spacer(Modifier.weight(1f))
                    Surface(
                        color = if (line.fromUser) AccentGreen.copy(alpha = 0.15f) else CardSurface,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            line.text,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                .widthIn(max = 600.dp),
                        )
                    }
                    if (!line.fromUser) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Вопрос или команда…", color = TextMuted) },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.send(input); input = "" },
                enabled = !state.busy && input.isNotBlank(),
            ) { Text("Отправить") }
        }
    }
}

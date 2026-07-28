package com.bydmate.app.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.radio.RadioSearchResult
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/**
 * Station finder over the public directories.
 *
 * Every row shows where the entry came from and, when the directory published a second stream, a
 * badge saying so — the open directories are user-submitted and uneven, so the driver deserves to
 * see the provenance before adding something to their list.
 */
@Composable
fun RadioSearchDialog(
    state: RadioViewModel.SearchState,
    sources: List<String>,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (RadioSearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.radio_search_title), color = TextPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.radio_search_field)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor = AccentGreen,
                            unfocusedLabelColor = TextMuted,
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSearch,
                        enabled = state.query.isNotBlank() && !state.loading,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.Black)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.radio_search_sources, sources.joinToString(", ")),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))

                when {
                    state.loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AccentGreen, strokeWidth = 2.dp)
                    }

                    state.results.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (state.searched) R.string.radio_search_empty
                                else R.string.radio_search_prompt
                            ),
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.results, key = { it.url }) { result ->
                            ResultRow(result = result, onPick = onPick)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
            }
        },
    )
}

@Composable
private fun ResultRow(
    result: RadioSearchResult,
    onPick: (RadioSearchResult) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBorder.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable { onPick(result) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Remote favicons load slowly and often 404; the debounce keeps a fast scroll from
        // firing a request per row, and the letter tile covers whatever never arrives.
        RadioStationIcon(
            iconUrl = result.iconUrl,
            fallbackText = result.name,
            modifier = Modifier.size(40.dp),
            debounceMs = 250,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = buildList {
                if (result.codec.isNotBlank()) add(result.codec)
                if (result.bitrate > 0) add("${result.bitrate} kbps")
                result.country?.let { add(it) }
                add(result.source)
                if (result.lowBitrateUrl != null) add("эконом ✓")
            }
            Text(
                text = details.joinToString(" · "),
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Outlined.Add, contentDescription = null, tint = AccentGreen)
    }
}

package com.bydmate.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.ui.places.PlaceEditDialog
import com.bydmate.app.ui.places.PlacesViewModel
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

@Composable
fun PlacesInlineContent(viewModel: PlacesViewModel = hiltViewModel()) {
    val places by viewModel.places.collectAsStateWithLifecycle()
    val usageCounts by viewModel.usageCounts.collectAsStateWithLifecycle()
    val tileSource by viewModel.mapTileSource.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Show Toast when deletion is blocked because place is in use.
    // The delete button is also disabled (inUse guard), so this branch is purely protective.
    LaunchedEffect(viewModel.deleteBlocked) {
        viewModel.deleteBlocked.collect { count ->
            Toast.makeText(
                context,
                context.getString(R.string.places_delete_blocked_message, count),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // null = not editing; non-null = editing that entity
    var editing by remember { mutableStateOf<PlaceEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Show dialog when editing an existing place or adding a new one
    if (editing != null) {
        PlaceEditDialog(
            initial = editing,
            tileSource = tileSource,
            onDismiss = { editing = null },
            onSave = { id, name, lat, lon, r ->
                viewModel.save(id, name, lat, lon, r)
                editing = null
            }
        )
    } else if (showAddDialog) {
        PlaceEditDialog(
            initial = null,
            tileSource = tileSource,
            onDismiss = { showAddDialog = false },
            onSave = { id, name, lat, lon, r ->
                viewModel.save(id, name, lat, lon, r)
                showAddDialog = false
            }
        )
    }

    SettingHint(stringResource(R.string.settings_places_inline_hint))

    // Plain Column + forEach — settings panel is already inside verticalScroll,
    // so LazyColumn would crash with nested scrollable containers.
    if (places.isEmpty()) {
        Text(
            text = stringResource(R.string.places_empty),
            color = TextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            places.forEach { place ->
                PlaceRow(
                    place = place,
                    usageCount = usageCounts[place.id] ?: 0,
                    onEdit = { editing = place },
                    onDelete = { viewModel.delete(place) }
                )
            }
        }
    }

    Button(
        onClick = { showAddDialog = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentGreen,
            contentColor = NavyDark
        )
    ) {
        Text(stringResource(R.string.places_add_button), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlaceRow(
    place: PlaceEntity,
    usageCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val inUse = usageCount > 0
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = place.name,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.places_row_coords, place.lat, place.lon, place.radiusM),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                if (inUse) {
                    Text(
                        text = stringResource(R.string.places_row_in_use, usageCount),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
            Row {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.places_edit_content_description),
                        tint = AccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    enabled = !inUse,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.places_delete_content_description),
                        tint = if (inUse) TextMuted else SocRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

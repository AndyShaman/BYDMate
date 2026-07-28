package com.bydmate.app.ui.radio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.RadioStationEntity
import com.bydmate.app.media.RadioController
import com.bydmate.app.media.RadioIconLoader
import com.bydmate.app.media.RadioPlayback
import com.bydmate.app.media.RadioPresets
import com.bydmate.app.media.RadioStatus
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * "Radio" tab: a grid of manually added internet radio stations. Tapping a station starts it in
 * [RadioPlayerService]; tapping the station that is already playing stops it.
 */
@Composable
fun RadioScreen(
    viewModel: RadioViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val playback by RadioController.state.collectAsStateWithLifecycle()
    val dataSaver by viewModel.dataSaver.collectAsStateWithLifecycle()
    val tracks = remember(stations, dataSaver) { stations.toTracks(context, dataSaver) }

    var editing by remember { mutableStateOf<RadioStationEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showFinder by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RadioStationEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        NowPlayingBar(
            playback = playback,
            canStep = stations.isNotEmpty(),
            onPrev = { RadioController.step(context, tracks, -1) },
            onNext = { RadioController.step(context, tracks, 1) },
            onStop = { RadioController.stop(context) },
            onAdd = {
                editing = null
                showEditor = true
            },
            onFind = { showFinder = true }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (stations.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.Radio,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.radio_empty), color = TextSecondary, fontSize = 16.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(stations, key = { _, station -> station.id }) { index, station ->
                    val isActive = playback.isActive && playback.stationId == station.id
                    StationTile(
                        station = station,
                        status = if (isActive) playback.status else RadioStatus.IDLE,
                        onClick = {
                            if (isActive) {
                                RadioController.stop(context)
                            } else {
                                RadioController.play(context, tracks, index)
                            }
                        },
                        onEdit = {
                            editing = station
                            showEditor = true
                        },
                        onDelete = { pendingDelete = station }
                    )
                }
            }
        }
    }

    if (showFinder) {
        val search by viewModel.search.collectAsStateWithLifecycle()
        RadioSearchDialog(
            state = search,
            sources = viewModel.directoryLabels,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::runSearch,
            onPick = { result ->
                viewModel.addFromDirectory(result)
                showFinder = false
                viewModel.clearSearch()
            },
            onDismiss = {
                showFinder = false
                viewModel.clearSearch()
            }
        )
    }

    if (showEditor) {
        val target = editing
        RadioEditDialog(
            initial = target,
            onDismiss = { showEditor = false },
            onSave = { id, name, url, iconUrl ->
                // The icon may have been replaced under the same URL — drop the cached copy.
                RadioIconLoader.invalidate(context, target?.iconUrl)
                RadioIconLoader.invalidate(context, iconUrl)
                viewModel.save(id, name, url, iconUrl)
                showEditor = false
            }
        )
    }

    pendingDelete?.let { station ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.radio_delete_confirm_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.radio_delete_confirm_text, station.name), color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    if (RadioController.state.value.stationId == station.id) {
                        RadioController.stop(context)
                    }
                    viewModel.delete(station)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.automation_delete_button), color = SocRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun NowPlayingBar(
    playback: RadioPlayback,
    canStep: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onAdd: () -> Unit,
    onFind: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val label = when (playback.status) {
                RadioStatus.BUFFERING -> stringResource(R.string.radio_status_buffering)
                RadioStatus.PLAYING -> stringResource(R.string.radio_status_playing)
                RadioStatus.PAUSED -> stringResource(R.string.radio_status_paused)
                RadioStatus.ERROR ->
                    playback.errorMessage ?: stringResource(R.string.radio_error_stream)
                RadioStatus.IDLE -> stringResource(R.string.radio_status_idle)
            }
            Text(
                text = playback.stationName.ifBlank { stringResource(R.string.nav_tab_radio) },
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                color = if (playback.status == RadioStatus.ERROR) SocRed else TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Prev/next stay available with nothing playing — either one starts the list.
        IconButton(onClick = onPrev, enabled = canStep) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.radio_action_prev),
                tint = if (canStep) TextSecondary else TextMuted
            )
        }
        IconButton(onClick = onNext, enabled = canStep) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.radio_action_next),
                tint = if (canStep) TextSecondary else TextMuted
            )
        }
        if (playback.isActive) {
            TextButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = null, tint = SocRed)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.radio_action_stop), color = SocRed)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        // Finder first: typing a name is the common case, pasting a stream URL the expert one.
        Button(
            onClick = onFind,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.radio_find_button), color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onAdd,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CardBorder)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = TextPrimary)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.radio_add_button), color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StationTile(
    station: RadioStationEntity,
    status: RadioStatus,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val active = status != RadioStatus.IDLE

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) AccentGreen else CardBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                RadioStationIcon(
                    iconUrl = station.iconUrl,
                    fallbackText = station.name,
                    modifier = Modifier.size(64.dp)
                )
                when (status) {
                    RadioStatus.BUFFERING ->
                        CircularProgressIndicator(color = AccentGreen, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    RadioStatus.PLAYING, RadioStatus.PAUSED ->
                        Icon(
                            imageVector = if (status == RadioStatus.PLAYING) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                                .padding(4.dp)
                        )
                    else -> Unit
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = station.name,
                color = if (active) AccentGreen else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.charges_action_edit), tint = TextMuted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.charges_action_edit)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.charges_action_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/** Station logo loaded from the URL/URI, falling back to the first letter of the station name. */
@Composable
fun RadioStationIcon(
    iconUrl: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    debounceMs: Long = 0L
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)

    // Built-in stations carry a bydmate://preset/… reference instead of an image: draw their
    // bundled logo — or a monogram tile where no usable logo exists — with no I/O and no
    // dependency on connectivity.
    val preset = remember(iconUrl) { RadioPresets.fromIconRef(iconUrl) }
    if (preset != null) {
        val logo = preset.logoRes
        if (logo != null) {
            // The logos are artwork for light backgrounds; the white plate is only visible
            // behind the wordmarks that are not square.
            Image(
                painter = painterResource(logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .clip(shape)
                    .background(Color.White)
                    .padding(2.dp)
            )
        } else {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(Color(preset.colorArgb)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = preset.monogram,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
        return
    }

    var bitmap by remember(iconUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(iconUrl) {
        if (debounceMs > 0) delay(debounceMs)
        bitmap = RadioIconLoader.load(context, iconUrl)
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape)
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(CardBorder),
            contentAlignment = Alignment.Center
        ) {
            val letter = fallbackText.trim().firstOrNull()?.uppercase()
            if (letter != null) {
                Text(letter, color = TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Outlined.Radio, contentDescription = null, tint = TextSecondary)
            }
        }
    }
}

package com.bydmate.app.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.bydmate.app.media.RadioStatus
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/**
 * Compact radio strip for the Dashboard: current station, prev/play-stop/next, and a tap on the
 * name to jump straight to any station. Renders nothing at all while the feature is switched off
 * in Settings, so an opted-out driver sees the dashboard exactly as before.
 *
 * Prev/next wrap around the station list, so the driver can cycle without looking away from the
 * road; with nothing playing, either button starts the first station.
 */
@Composable
fun DashboardRadioControl(
    modifier: Modifier = Modifier,
    viewModel: RadioViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    if (!enabled) return

    val context = LocalContext.current
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val playback by RadioController.state.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }

    val dataSaver by viewModel.dataSaver.collectAsStateWithLifecycle()
    val tracks = remember(stations, dataSaver) { stations.toTracks(context, dataSaver) }
    val activeIndex = stations.indexOfFirst { it.id == playback.stationId }.takeIf { it >= 0 }
    val active = activeIndex?.let { stations[it] }.takeIf { playback.isActive }

    fun play(station: RadioStationEntity) {
        val index = stations.indexOfFirst { it.id == station.id }.takeIf { it >= 0 } ?: return
        RadioController.play(context, tracks, index)
    }

    fun step(delta: Int) = RadioController.step(context, tracks, delta)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(CardSurface, RoundedCornerShape(12.dp))
            .border(1.dp, if (active != null) AccentGreen else CardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioStationIcon(
            iconUrl = active?.iconUrl,
            fallbackText = active?.name ?: stringResource(R.string.nav_tab_radio),
            modifier = Modifier.size(40.dp)
        )

        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = stations.isNotEmpty()) { pickerOpen = true }
            ) {
                Text(
                    text = active?.name
                        ?: stringResource(
                            if (stations.isEmpty()) R.string.radio_empty_short
                            else R.string.radio_status_idle
                        ),
                    color = if (active != null) TextPrimary else TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val status = when (playback.status) {
                    RadioStatus.BUFFERING -> stringResource(R.string.radio_status_buffering)
                    RadioStatus.PLAYING -> stringResource(R.string.radio_status_playing)
                    RadioStatus.PAUSED -> stringResource(R.string.radio_status_paused)
                    RadioStatus.ERROR ->
                        playback.errorMessage ?: stringResource(R.string.radio_error_stream)
                    RadioStatus.IDLE ->
                        if (stations.isEmpty()) "" else stringResource(R.string.radio_pick_station)
                }
                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        color = if (playback.status == RadioStatus.ERROR) SocRed else TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                stations.forEach { station ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                station.name,
                                color = if (station.id == active?.id) AccentGreen else TextPrimary
                            )
                        },
                        onClick = {
                            pickerOpen = false
                            play(station)
                        }
                    )
                }
            }
        }

        IconButton(onClick = { step(-1) }, enabled = stations.isNotEmpty()) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.radio_action_prev),
                tint = if (stations.isEmpty()) TextMuted else TextSecondary
            )
        }
        IconButton(
            onClick = {
                when {
                    playback.isActive -> RadioController.stop(context)
                    stations.isNotEmpty() -> RadioController.play(context, tracks, activeIndex ?: 0)
                }
            },
            enabled = stations.isNotEmpty()
        ) {
            if (playback.status == RadioStatus.BUFFERING) {
                CircularProgressIndicator(
                    color = AccentGreen,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector = if (playback.isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playback.isActive) R.string.radio_action_stop else R.string.radio_action_play
                    ),
                    tint = if (stations.isEmpty()) TextMuted else AccentGreen
                )
            }
        }
        IconButton(onClick = { step(1) }, enabled = stations.isNotEmpty()) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.radio_action_next),
                tint = if (stations.isEmpty()) TextMuted else TextSecondary
            )
        }
    }
}

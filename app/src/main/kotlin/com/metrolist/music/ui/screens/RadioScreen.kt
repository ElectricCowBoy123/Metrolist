/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.metadata
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.MediaMetadataListItem
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.RadioViewModel

const val RADIO_ROUTE = "radio?videoId={videoId}&playlistId={playlistId}&params={params}&title={title}"

/**
 * Opens the radio for [endpoint] as a page instead of playing it straight away, so whatever is
 * currently playing keeps playing until the user presses play.
 */
fun NavController.navigateToRadio(
    endpoint: WatchEndpoint,
    title: String? = null,
) {
    val query =
        listOf(
            "videoId" to endpoint.videoId,
            "playlistId" to endpoint.playlistId,
            "params" to endpoint.params,
            "title" to title,
        ).filter { it.second != null }
            .joinToString("&") { (key, value) -> "$key=${Uri.encode(value)}" }
    navigate("radio?$query")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    navController: NavController,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val loaded = state as? RadioViewModel.State.Loaded
    val title = loaded?.title ?: stringResource(R.string.start_radio)

    fun play(index: Int) {
        viewModel.queueFor(index)?.let(playerConnection::playQueue)
    }

    // The radio of the song that's already playing carries on from it instead of restarting it.
    fun startRadio(shuffle: Boolean = false) {
        val queue = viewModel.queueFor(shuffle = shuffle) ?: return
        if (viewModel.seedVideoId == mediaMetadata?.id) {
            playerConnection.continueWithQueue(queue)
        } else {
            playerConnection.playQueue(queue)
        }
    }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) {
        when (val current = state) {
            RadioViewModel.State.Loading -> {
                item(key = "loading") {
                    ShimmerHost {
                        repeat(8) { ListItemPlaceHolder() }
                    }
                }
            }

            RadioViewModel.State.Error -> {
                item(key = "error") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.radio_loading_failed),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.size(16.dp))
                        OutlinedButton(onClick = viewModel::load) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }

            is RadioViewModel.State.Loaded -> {
                item(key = "header") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                pluralStringResource(
                                    R.plurals.n_song,
                                    current.status.items.size,
                                    current.status.items.size,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.size(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { startRadio() }, modifier = Modifier.weight(1f)) {
                                Icon(
                                    painter = painterResource(R.drawable.play),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.radio_play))
                            }
                            OutlinedButton(onClick = { startRadio(shuffle = true) }, modifier = Modifier.weight(1f)) {
                                Icon(
                                    painter = painterResource(R.drawable.shuffle),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.shuffle))
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = current.status.items,
                    key = { index, item -> "${item.mediaId}_$index" },
                ) { index, item ->
                    val metadata = item.metadata ?: return@itemsIndexed
                    MediaMetadataListItem(
                        mediaMetadata = metadata,
                        isActive = metadata.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        modifier =
                            Modifier.clickable {
                                if (metadata.id == mediaMetadata?.id) {
                                    playerConnection.togglePlayPause()
                                } else {
                                    play(index)
                                }
                            },
                    )
                }
            }
        }
    }

    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

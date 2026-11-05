/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.GenreLookupProgress
import com.metrolist.music.ui.component.LibrarySearchEmptyPlaceholder
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.viewmodels.LibraryGenresViewModel

// Stands in for the group of songs without a known genre, which has a null name.
private const val UNKNOWN_GENRE_KEY = ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryGenresScreen(
    onDeselect: () -> Unit,
    viewModel: LibraryGenresViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val progress by viewModel.genreLookupProgress.collectAsStateWithLifecycle()

    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = groups?.firstOrNull { (it.genre ?: UNKNOWN_GENRE_KEY) == selectedKey }
    val unknownGenre = stringResource(R.string.unknown_genre)

    if (selected != null) {
        BackHandler { selectedKey = null }
    }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) {
        if (selected == null) {
            item(key = "filter") {
                Row {
                    Spacer(Modifier.width(12.dp))
                    FilterChip(
                        label = { Text(stringResource(R.string.filter_genres)) },
                        selected = true,
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                        onClick = onDeselect,
                        shape = RoundedCornerShape(16.dp),
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.close), contentDescription = "")
                        },
                    )
                }
            }

            progress?.let {
                item(key = "lookup_progress") {
                    GenreLookupProgress(progress = it)
                }
            }

            if (groups?.isEmpty() == true) {
                item(key = "empty_placeholder") {
                    LibrarySearchEmptyPlaceholder(
                        icon = R.drawable.queue_music,
                        text = stringResource(R.string.library_genre_empty),
                    )
                }
            }

            items(
                items = groups.orEmpty(),
                key = { it.genre ?: UNKNOWN_GENRE_KEY },
            ) { group ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = group.genre ?: unknownGenre,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.n_song, group.songs.size, group.songs.size))
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { selectedKey = group.genre ?: UNKNOWN_GENRE_KEY }
                            .animateItem(),
                )
            }
        } else {
            item(key = "genre_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    IconButton(onClick = { selectedKey = null }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                    Text(
                        text = selected.genre ?: unknownGenre,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = selected.genre ?: unknownGenre,
                                    items = selected.songs.shuffled().map { it.toMediaItem() },
                                ),
                            )
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = stringResource(R.string.shuffle),
                        )
                    }
                }
            }

            itemsIndexed(
                items = selected.songs,
                key = { _, song -> song.id },
            ) { index, song ->
                SongListItem(
                    song = song,
                    showInLibraryIcon = true,
                    isActive = song.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                    showLikedIcon = true,
                    trailingContent = {
                        IconButton(
                            onClick = {
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (song.id == mediaMetadata?.id) {
                                    playerConnection.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = selected.genre ?: unknownGenre,
                                            items = selected.songs.map { it.toMediaItem() },
                                            startIndex = index,
                                        ),
                                    )
                                }
                            }.animateItem(),
                )
            }
        }
    }
}

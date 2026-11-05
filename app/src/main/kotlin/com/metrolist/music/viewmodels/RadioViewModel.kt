/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.playback.queues.PreparedQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loads a radio without playing it, so starting a radio doesn't interrupt what's playing.
 * The user starts it from the radio screen, like Spotify's song radio.
 */
@HiltViewModel
class RadioViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val endpoint =
        WatchEndpoint(
            videoId = savedStateHandle.get<String>("videoId"),
            playlistId = savedStateHandle.get<String>("playlistId"),
            params = savedStateHandle.get<String>("params"),
        )
    private val fallbackTitle = savedStateHandle.get<String>("title")

    /** The song this radio was started from, if any. */
    val seedVideoId: String? = endpoint.videoId

    sealed interface State {
        data object Loading : State

        data object Error : State

        data class Loaded(val title: String?, val status: Queue.Status) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    private var queue: YouTubeQueue? = null

    init {
        load()
    }

    fun load() {
        _state.value = State.Loading
        viewModelScope.launch {
            try {
                val radioQueue = YouTubeQueue(endpoint)
                val status =
                    radioQueue
                        .getInitialStatus()
                        .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                queue = radioQueue
                _state.value =
                    if (status.items.isEmpty()) {
                        State.Error
                    } else {
                        State.Loaded(title = status.title ?: fallbackTitle, status = status)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                _state.value = State.Error
            }
        }
    }

    /** The loaded radio as a queue starting at [index], or shuffled when [shuffle] is set. */
    fun queueFor(index: Int = 0, shuffle: Boolean = false): Queue? {
        val loaded = _state.value as? State.Loaded ?: return null
        val items = if (shuffle) loaded.status.items.shuffled() else loaded.status.items
        return PreparedQueue(
            status =
                loaded.status.copy(
                    items = items,
                    mediaItemIndex = if (shuffle) 0 else index.coerceIn(0, items.lastIndex),
                    position = 0L,
                ),
            source = queue,
        )
    }
}

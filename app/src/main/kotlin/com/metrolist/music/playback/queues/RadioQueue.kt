/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps a radio apart from the user's queue, like Spotify's "Next from" section.
 *
 * The radio's songs stay in [upNext] rather than in the player, so what's after the current song in
 * the player is only what the user queued. The exception is the first song of [upNext], the
 * lookahead, which stays last in the player so skipping and gapless playback work. It moves on as
 * each radio song starts, and the queue screen shows it under "Next from".
 *
 * Shuffling a radio shuffles [upNext]. The player itself stays in order, so queued songs always
 * play before the lookahead.
 */
class RadioQueue(
    private val player: () -> ExoPlayer,
) {
    private val _upNext = MutableStateFlow<List<MediaItem>>(emptyList())
    val upNext: StateFlow<List<MediaItem>> = _upNext.asStateFlow()

    private var originalOrder = emptyList<MediaItem>()

    /** Whether a radio is running, even if [upNext] is empty until its next page has loaded. */
    var isActive = false
        private set

    fun start(songs: List<MediaItem>) {
        reset()
        isActive = true
        originalOrder = songs
        setUpNext(if (player().shuffleModeEnabled) songs.shuffled() else songs)
    }

    /** Picks up a saved radio, whose lookahead is normally already in the restored player. */
    fun restore(songs: List<MediaItem>) {
        reset()
        if (songs.isEmpty()) return
        isActive = true
        originalOrder = songs
        _upNext.value = songs
        if (lookaheadIndex() == C.INDEX_UNSET) {
            addLookahead(songs.first())
        } else {
            keepPlayerInOrder()
        }
    }

    /** Forgets the radio, for when the player's items have already been replaced. */
    fun reset() {
        isActive = false
        originalOrder = emptyList()
        _upNext.value = emptyList()
    }

    /** Ends the radio and takes its lookahead out of the player. */
    fun stop() {
        val lookahead = lookaheadIndex()
        reset()
        if (lookahead != C.INDEX_UNSET) {
            player().removeMediaItem(lookahead)
        }
    }

    /** Where a song the user queues goes, so it plays before the radio carries on. */
    fun queueInsertIndex(): Int {
        val lookahead = lookaheadIndex()
        return if (lookahead == C.INDEX_UNSET) player().mediaItemCount else lookahead
    }

    /** The songs still to play in the player, in the order they'll play. */
    fun upcomingIndices(): List<Int> {
        val player = player()
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = player.shuffleModeEnabled
        return generateSequence(
            timeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, shuffle),
        ) {
            timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, shuffle)
        }.takeWhile { it != C.INDEX_UNSET }.toList()
    }

    /** Removes the songs the user queued. The radio carries on after the current song. */
    fun clearUserQueue() {
        val lookahead = lookaheadIndex()
        upcomingIndices()
            .filter { it != lookahead }
            .sortedDescending()
            .forEach(player()::removeMediaItem)
    }

    /** Skips to the song at [index] of [upNext]. Queued songs stay queued. */
    fun playFrom(index: Int) {
        val remaining = _upNext.value.drop(index)
        if (remaining.isEmpty()) return

        val player = player()
        val lookahead = lookaheadIndex()
        if (lookahead != C.INDEX_UNSET) {
            player.removeMediaItem(lookahead)
        }
        _upNext.value = remaining
        val position = player.currentMediaItemIndex + 1
        player.addMediaItem(position, remaining.first())
        keepPlayerInOrder()
        player.seekToDefaultPosition(position)
        player.playWhenReady = true
    }

    fun removeSongs(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) return
        originalOrder = originalOrder.filter { it.mediaId !in mediaIds }
        val remaining = _upNext.value.filter { it.mediaId !in mediaIds }
        if (remaining.firstOrNull()?.mediaId == _upNext.value.firstOrNull()?.mediaId) {
            _upNext.value = remaining
        } else {
            setUpNext(remaining)
        }
    }

    /** Adds the radio's next page, without songs it already has. */
    fun addPage(songs: List<MediaItem>) {
        val known = originalOrder.mapTo(mutableSetOf()) { it.mediaId }
        val newSongs = songs.filter { known.add(it.mediaId) }
        if (newSongs.isEmpty()) return

        originalOrder = originalOrder + newSongs
        val current = _upNext.value
        val wasEmpty = current.isEmpty()
        _upNext.value =
            if (player().shuffleModeEnabled) {
                current.take(1) + (current.drop(1) + newSongs).shuffled()
            } else {
                current + newSongs
            }
        if (wasEmpty) {
            addLookahead(_upNext.value.first())
        }
    }

    fun onShuffleModeChanged(enabled: Boolean) {
        keepPlayerInOrder()
        val current = _upNext.value
        if (enabled) {
            setUpNext(current.shuffled())
        } else {
            val ids = current.mapTo(mutableSetOf()) { it.mediaId }
            setUpNext(originalOrder.filter { it.mediaId in ids })
        }
    }

    /** Makes the player's shuffle order match its item order. */
    fun keepPlayerInOrder() {
        val player = player()
        if (player.shuffleModeEnabled) {
            player.setShuffleOrder(
                DefaultShuffleOrder(IntArray(player.mediaItemCount) { it }, System.currentTimeMillis()),
            )
        }
    }

    /** Call when the player moves to another song, to move the radio on when it's one of its own. */
    fun onMediaItemTransition(mediaItem: MediaItem?) {
        if (mediaItem == null) {
            reset()
            return
        }
        val first = _upNext.value.firstOrNull() ?: return
        val lastMatch = lastIndexOf(first.mediaId)
        if (lastMatch == C.INDEX_UNSET) {
            // Something else replaced the player's items, so this radio is no longer playing
            reset()
            return
        }
        if (mediaItem.mediaId == first.mediaId && player().currentMediaItemIndex == lastMatch) {
            val remaining = _upNext.value.drop(1)
            _upNext.value = remaining
            remaining.firstOrNull()?.let(::addLookahead)
        }
    }

    private fun setUpNext(songs: List<MediaItem>) {
        val lookahead = lookaheadIndex()
        if (lookahead != C.INDEX_UNSET) {
            player().removeMediaItem(lookahead)
        }
        _upNext.value = songs
        songs.firstOrNull()?.let(::addLookahead)
    }

    private fun addLookahead(song: MediaItem) {
        player().addMediaItem(song)
        keepPlayerInOrder()
    }

    private fun lookaheadIndex(): Int {
        val mediaId = _upNext.value.firstOrNull()?.mediaId ?: return C.INDEX_UNSET
        val lastMatch = lastIndexOf(mediaId)
        return if (lastMatch == player().currentMediaItemIndex) C.INDEX_UNSET else lastMatch
    }

    private fun lastIndexOf(mediaId: String): Int {
        val player = player()
        return (player.mediaItemCount - 1 downTo 0).firstOrNull {
            player.getMediaItemAt(it).mediaId == mediaId
        } ?: C.INDEX_UNSET
    }
}

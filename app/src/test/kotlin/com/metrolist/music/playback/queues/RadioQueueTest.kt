/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RadioQueueTest {
    private lateinit var player: ExoPlayer
    private lateinit var radio: RadioQueue

    private fun song(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.test/$id").build()

    private fun songs(vararg ids: String) = ids.map(::song)

    private fun playerIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun upNextIds() = radio.upNext.value.map { it.mediaId }

    /** Plays the next song in the player, as if the current one had finished. */
    private fun skipToNext() {
        player.seekToNextMediaItem()
        radio.onMediaItemTransition(player.currentMediaItem)
    }

    private fun currentId() = player.currentMediaItem?.mediaId

    private fun queue(id: String) {
        player.addMediaItem(radio.queueInsertIndex(), song(id))
        radio.keepPlayerInOrder()
    }

    @Before
    fun setUp() {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        radio = RadioQueue { player }
        player.setMediaItem(song("current"))
    }

    @After
    fun tearDown() {
        player.release()
    }

    @Test
    fun `only the next radio song is in the player`() {
        radio.start(songs("a", "b", "c"))

        assertEquals(listOf("current", "a"), playerIds())
        assertEquals(listOf("a", "b", "c"), upNextIds())
    }

    @Test
    fun `the radio moves on as its songs play`() {
        radio.start(songs("a", "b", "c"))

        skipToNext()

        assertEquals("a", currentId())
        assertEquals(listOf("current", "a", "b"), playerIds())
        assertEquals(listOf("b", "c"), upNextIds())

        skipToNext()
        skipToNext()

        assertEquals("c", currentId())
        assertTrue(upNextIds().isEmpty())
        assertTrue(radio.isActive)
    }

    @Test
    fun `queued songs play before the radio`() {
        radio.start(songs("a", "b"))

        queue("mine1")
        queue("mine2")

        assertEquals(listOf("current", "mine1", "mine2", "a"), playerIds())

        skipToNext()
        assertEquals("mine1", currentId())
        assertEquals(listOf("a", "b"), upNextIds())

        skipToNext()
        skipToNext()
        assertEquals("a", currentId())
        assertEquals(listOf("b"), upNextIds())
    }

    @Test
    fun `clearing the queue removes queued songs and the radio carries on`() {
        radio.start(songs("a", "b"))
        queue("mine1")
        queue("mine2")

        radio.clearUserQueue()

        assertEquals(listOf("current", "a"), playerIds())
        assertEquals(listOf("a", "b"), upNextIds())

        skipToNext()
        assertEquals("a", currentId())
        skipToNext()
        assertEquals("b", currentId())
    }

    @Test
    fun `a queued song that is also next in the radio doesn't move the radio on`() {
        radio.start(songs("a", "b"))
        queue("a")

        skipToNext()

        assertEquals(listOf("a", "b"), upNextIds())

        skipToNext()
        assertEquals(listOf("b"), upNextIds())
    }

    @Test
    fun `tapping a radio song skips earlier ones and keeps queued songs next`() {
        radio.start(songs("a", "b", "c", "d"))
        queue("mine")

        radio.playFrom(2)
        radio.onMediaItemTransition(player.currentMediaItem)

        assertEquals("c", currentId())
        assertEquals(listOf("current", "c", "mine", "d"), playerIds())
        assertEquals(listOf("d"), upNextIds())

        skipToNext()
        assertEquals("mine", currentId())
    }

    @Test
    fun `shuffling keeps queued songs first and turning it off restores the radio's order`() {
        val ids = (1..30).map { "s$it" }
        radio.start(ids.map(::song))
        queue("mine")

        player.shuffleModeEnabled = true
        radio.onShuffleModeChanged(true)

        assertEquals(ids.toSet(), upNextIds().toSet())
        assertEquals(listOf("current", "mine", upNextIds().first()), playerIds())

        val firstShuffled = upNextIds().first()
        skipToNext()
        assertEquals("mine", currentId())
        skipToNext()
        assertEquals(firstShuffled, currentId())

        player.shuffleModeEnabled = false
        radio.onShuffleModeChanged(false)

        assertEquals(ids.filter { it != firstShuffled }, upNextIds())
        assertEquals(upNextIds().first(), playerIds().last())
    }

    @Test
    fun `removing the next radio song replaces it in the player`() {
        radio.start(songs("a", "b", "c"))

        radio.removeSongs(setOf("a", "c"))

        assertEquals(listOf("current", "b"), playerIds())
        assertEquals(listOf("b"), upNextIds())
    }

    @Test
    fun `stopping the radio leaves queued songs`() {
        radio.start(songs("a", "b"))
        queue("mine")

        radio.stop()

        assertEquals(listOf("current", "mine"), playerIds())
        assertFalse(radio.isActive)
    }

    @Test
    fun `new pages skip songs the radio already has`() {
        radio.start(songs("a"))
        skipToNext()
        assertTrue(upNextIds().isEmpty())

        radio.addPage(songs("a", "b", "c", "b"))

        assertEquals(listOf("b", "c"), upNextIds())
        assertEquals(listOf("current", "a", "b"), playerIds())
    }

    @Test
    fun `replacing the player's items ends the radio`() {
        radio.start(songs("a", "b"))

        player.setMediaItem(song("other"))
        radio.onMediaItemTransition(player.currentMediaItem)

        assertTrue(upNextIds().isEmpty())
        assertFalse(radio.isActive)
    }

    @Test
    fun `restoring doesn't add the lookahead twice`() {
        player.setMediaItems(songs("current", "a"))

        radio.restore(songs("a", "b"))

        assertEquals(listOf("current", "a"), playerIds())
        assertEquals(listOf("a", "b"), upNextIds())
    }
}

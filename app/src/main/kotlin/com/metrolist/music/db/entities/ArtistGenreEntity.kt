/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached genre for an artist, looked up from MusicBrainz.
 * [genre] is null when the lookup ran but found nothing, so it isn't retried until [fetchedAt] is stale.
 */
@Immutable
@Entity(tableName = "artist_genre")
data class ArtistGenreEntity(
    @PrimaryKey val artistId: String,
    val genre: String?,
    val fetchedAt: Long,
)

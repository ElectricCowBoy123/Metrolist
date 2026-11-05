/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import com.metrolist.music.db.entities.ArtistGenreEntity
import com.metrolist.music.db.entities.Song
import java.text.Collator
import java.util.Locale

/** artistId -> genre, only for artists whose lookup found a genre. */
fun List<ArtistGenreEntity>.toGenreMap(): Map<String, String> =
    mapNotNull { entity -> entity.genre?.let { entity.artistId to it } }.toMap()

/** The genre of the first credited artist that has one. */
fun Song.genre(genres: Map<String, String>): String? =
    orderedArtists.firstNotNullOfOrNull { genres[it.id] }

/**
 * Groups songs by genre (A to Z, or Z to A when [descending]), then by artist, album and title.
 * Songs whose genre is unknown always go last.
 *
 * The DAO sort functions reverse their result when descending, so this returns the list
 * pre-reversed in that case to end up in the order described above.
 */
fun List<Song>.sortedByGenreForDao(
    genres: Map<String, String>,
    descending: Boolean,
): List<Song> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val withinGenre =
        compareBy<Song, String>(collator) { song -> song.orderedArtists.joinToString("") { it.name } }
            .thenBy(collator) { it.album?.title.orEmpty() }
            .thenBy(collator) { it.song.title }

    val (known, unknown) = partition { it.genre(genres) != null }
    val genreOrder = compareBy<Song, String>(collator) { it.genre(genres)!! }
    val sortedKnown =
        known.sortedWith(if (descending) genreOrder.reversed().then(withinGenre) else genreOrder.then(withinGenre))
    val result = sortedKnown + unknown.sortedWith(withinGenre)
    return if (descending) result.asReversed() else result
}

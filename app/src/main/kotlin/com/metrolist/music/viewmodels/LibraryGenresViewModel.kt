/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.genre
import com.metrolist.music.db.toGenreMap
import com.metrolist.music.extensions.filterExplicit
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.genre.ArtistGenreFetcher
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/** The user's liked and library songs, grouped by the genre of their first artist. */
@HiltViewModel
class LibraryGenresViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    genreFetcher: ArtistGenreFetcher,
) : ViewModel() {
    /** Songs of one genre. A null [genre] holds the songs whose genre isn't known. */
    data class GenreGroup(val genre: String?, val songs: List<Song>)

    val genreLookupProgress = genreFetcher.progress

    private val songs =
        combine(
            database.likedSongs(SongSortType.CREATE_DATE, true),
            database.songs(SongSortType.CREATE_DATE, true),
            context.dataStore.data,
        ) { liked, library, preferences ->
            (liked + library)
                .distinctBy { it.id }
                .filterExplicit(preferences[HideExplicitKey] ?: false)
                .filterVideoSongs(preferences[HideVideoSongsKey] ?: false)
        }.onEach { songs ->
            genreFetcher.fetchMissing(songs.mapNotNull { it.orderedArtists.firstOrNull() })
        }

    /** Null until the songs have loaded. */
    val groups =
        combine(songs, database.artistGenres()) { songs, artistGenres ->
            val genres = artistGenres.toGenreMap()
            val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
            val (known, unknown) =
                songs
                    .groupBy { it.genre(genres) }
                    .map { (genre, songs) -> GenreGroup(genre, songs) }
                    .partition { it.genre != null }
            known.sortedWith(compareBy<GenreGroup, String>(collator) { it.genre!! }) + unknown
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)
}

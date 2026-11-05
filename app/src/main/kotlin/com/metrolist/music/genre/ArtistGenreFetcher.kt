/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.genre

import com.metrolist.music.BuildConfig
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.ArtistGenreEntity
import com.metrolist.music.di.ApplicationScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up artist genres on MusicBrainz (free, no API key) and caches them in the database,
 * so songs can be sorted by genre. YouTube Music doesn't expose a genre per song.
 *
 * MusicBrainz asks clients to stay at one request per second, so lookups run one at a time
 * in the background and the song list re-sorts itself as genres arrive.
 */
@Singleton
class ArtistGenreFetcher
@Inject
constructor(
    private val database: MusicDatabase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class Progress(val done: Int, val total: Int)

    private val _progress = MutableStateFlow<Progress?>(null)

    /** Non-null while a lookup batch is running. */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private var job: Job? = null

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            defaultRequest {
                url("https://musicbrainz.org/ws/2/")
                headers.append(
                    HttpHeaders.UserAgent,
                    "Metrolist/${BuildConfig.VERSION_NAME} ( https://github.com/MetrolistGroup/Metrolist )",
                )
            }
            expectSuccess = false
        }
    }

    /**
     * Fetches genres for any of [artists] without a recent lookup. Does nothing if a batch is
     * already running; the next call after it finishes picks up anything it missed.
     */
    fun fetchMissing(artists: Collection<ArtistEntity>) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val since = System.currentTimeMillis() - RETRY_AFTER_MS
                val fresh = database.artistIdsWithFreshGenre(since).toHashSet()
                val todo = artists
                    .distinctBy { it.id }
                    .filter { it.id !in fresh && it.name.isNotBlank() }
                if (todo.isEmpty()) return@launch

                _progress.value = Progress(0, todo.size)
                todo.forEachIndexed { index, artist ->
                    val result = runCatching { lookUpGenre(artist.name) }
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    result
                        .onSuccess { genre ->
                            database.upsertArtistGenre(
                                ArtistGenreEntity(
                                    artistId = artist.id,
                                    genre = genre,
                                    fetchedAt = System.currentTimeMillis(),
                                ),
                            )
                        }.onFailure {
                            // Not cached, so it's retried next time.
                            Timber.tag(TAG).w(it, "Genre lookup failed for ${artist.name}")
                        }
                    _progress.value = Progress(index + 1, todo.size)
                    if (result.exceptionOrNull() is IOException) return@launch // offline, stop
                }
            } finally {
                _progress.value = null
            }
        }
    }

    /** Returns the artist's most-voted MusicBrainz genre, or null if none was found. */
    private suspend fun lookUpGenre(artistName: String): String? {
        val escaped = artistName.replace("\\", "\\\\").replace("\"", "\\\"")
        val search: SearchResponse = rateLimitedGet("artist/") {
            parameter("query", "artist:\"$escaped\"")
            parameter("limit", 5)
            parameter("fmt", "json")
        }.body()

        val match =
            search.artists.firstOrNull { it.score >= 90 && it.name.equals(artistName, ignoreCase = true) }
                ?: search.artists.firstOrNull { it.score == 100 }
                ?: return null

        val details: MbArtist = rateLimitedGet("artist/${match.id}") {
            parameter("inc", "genres")
            parameter("fmt", "json")
        }.body()

        return details.genres
            .maxByOrNull { it.count }
            ?.name
            ?.replaceFirstChar { it.titlecase() }
    }

    private suspend fun rateLimitedGet(
        path: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        repeat(3) { attempt ->
            delay(REQUEST_INTERVAL_MS * (attempt + 1))
            val response = client.get(path, block)
            if (response.status.isSuccess()) return response
            if (response.status != HttpStatusCode.ServiceUnavailable) {
                throw IllegalStateException("MusicBrainz returned ${response.status}")
            }
        }
        throw IllegalStateException("MusicBrainz is rate limiting requests")
    }

    @Serializable
    private data class SearchResponse(val artists: List<MbArtist> = emptyList())

    @Serializable
    private data class MbArtist(
        val id: String,
        val name: String = "",
        val score: Int = 0,
        val genres: List<MbGenre> = emptyList(),
    )

    @Serializable
    private data class MbGenre(val name: String, val count: Int = 0)

    private companion object {
        const val TAG = "ArtistGenreFetcher"
        const val REQUEST_INTERVAL_MS = 1_100L
        val RETRY_AFTER_MS = TimeUnit.DAYS.toMillis(60)
    }
}

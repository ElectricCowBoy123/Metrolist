/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata

/**
 * A queue whose first page was already loaded (for example a radio shown on screen before
 * playing it). Plays exactly what the user saw, then keeps paging from [source].
 */
class PreparedQueue(
    private val status: Queue.Status,
    private val source: Queue? = null,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override val isRadio: Boolean = true

    override suspend fun getInitialStatus(): Queue.Status = status

    override fun hasNextPage(): Boolean = source?.hasNextPage() == true

    override suspend fun nextPage(): List<MediaItem> = source?.nextPage() ?: emptyList()
}

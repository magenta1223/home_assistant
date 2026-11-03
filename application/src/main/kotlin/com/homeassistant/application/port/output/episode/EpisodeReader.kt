package com.homeassistant.application.port.output.episode

import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.identity.UserId

/** Reads canonical-episode contexts for internal indexing and authorized retrieval. */
interface EpisodeReader {
    /** Loads only contexts visible to the requesting user. */
    fun getEpisodes(userId: UserId): List<Episode>
}
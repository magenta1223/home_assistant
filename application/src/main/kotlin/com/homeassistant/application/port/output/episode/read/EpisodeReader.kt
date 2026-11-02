package com.homeassistant.application.port.output.episode.read

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode

/** Reads canonical-episode contexts for internal indexing and authorized retrieval. */
interface EpisodeReader {
    /** Loads only contexts visible to the requesting user. */
    fun getEpisodes(userId: UserId): List<Episode>
}

package com.homeassistant.application.port.outbound.memoryextraction.component

import com.homeassistant.domain.component.Component
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.identity.UserId

/** The long-term [statements] extracted from episode [episodeId]; empty when it held none. */
data class EpisodeComponents(val episodeId: Int, val statements: List<String>)

/**
 * Stored Components. Every condition is applied by the storage, so no caller filters what it has already read.
 * Writes are trusted ingestion, never an external mutation API.
 */
interface ComponentRepository {
    /** The Components with [ids], oldest first; an unknown id is left out. */
    fun findByIds(ids: Collection<Int>): List<Component>

    /** The Components every reader of [access] may read, oldest first. */
    fun findCovering(access: EpisodeAccess): List<Component>

    /** The Components [userId] can read, oldest first. */
    fun findVisibleTo(userId: UserId): List<Component>

    /**
     * Marks the episode of [extraction] as extracted and stores its statements as Components in one write; returns them
     * in statement order. An episode with no long-term knowledge is still marked, so it is never extracted again.
     */
    fun save(extraction: EpisodeComponents): List<Component>
}

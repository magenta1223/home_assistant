package com.homeassistant.domain.episode

import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable

/**
 * Evidence-backed, immutable content with independently managed access.
 *
 * The content holds what happened, without predefined categories. Who sent it and when stay in the evidence.
 * Organization belongs to Component references.
 */
@Serializable
data class Episode(
    val id: Int,
    val createdByUserId: String,
    /** A short searchable name of what happened. */
    val title: String,
    val content: String,
    val visibility: EpisodeVisibility,
    val allowedUserIds: Set<String> = emptySet(),
    val evidenceRefs: List<Int>,
    /** Storage creation time in epoch milliseconds; this is not the event time described by the episode. */
    val createdAt: Long,
) {
    init {
        require(title.isNotBlank()) { "episode title is required" }
        require(content.isNotBlank()) { "episode content is required" }
        require(createdByUserId.isNotBlank()) { "createdByUserId is required" }
        EpisodeAccess(visibility, allowedUserIds)
        require(evidenceRefs.isNotEmpty()) { "episode evidence is required" }
        require(createdAt >= 0) { "episode creation time must not be negative" }
    }

    fun isVisibleTo(requester: UserId): Boolean = access.isVisibleTo(requester)

    val access: EpisodeAccess
        get() = EpisodeAccess(visibility, allowedUserIds)
}

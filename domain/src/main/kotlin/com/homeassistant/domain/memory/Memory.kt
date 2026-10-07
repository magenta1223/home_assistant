package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable

/**
 * Evidence-backed, immutable content with independently managed access.
 *
 * Attribution, time, conditions and uncertainty belong in the content, without
 * predefined categories. Organization belongs to Topic references.
 */
@Serializable
data class Memory(
    val id: Int,
    val createdByUserId: String,
    val content: String,
    val visibility: MemoryVisibility,
    val allowedUserIds: Set<String> = emptySet(),
    val evidenceRefs: List<Int>,
    /** Storage creation time in epoch milliseconds; this is not the event time described by the memory. */
    val createdAt: Long,
) {
    init {
        require(content.isNotBlank()) { "memory content is required" }
        require(createdByUserId.isNotBlank()) { "createdByUserId is required" }
        MemoryAccess(visibility, allowedUserIds)
        require(evidenceRefs.isNotEmpty()) { "memory evidence is required" }
        require(createdAt >= 0) { "memory creation time must not be negative" }
    }

    fun isVisibleTo(requester: UserId): Boolean = access.isVisibleTo(requester)

    val access: MemoryAccess
        get() = MemoryAccess(visibility, allowedUserIds)
}

package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable

/**
 * An approved, independently searchable household memory.
 *
 * A memory with children is a short description of a knowledge area; a memory
 * without children is an atomic fact. Both are represented by this same type.
 */
@Serializable
data class Memory(
    val id: Int,
    val childrenIds: List<Int> = emptyList(),
    val createdByUserId: String,
    val content: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: MemoryCertainty,
    val visibility: MemoryVisibility,
    val allowedUserIds: Set<String> = emptySet(),
    val evidenceRefs: List<Int>,
    /** Storage creation time in epoch milliseconds; this is not the event time described by the memory. */
    val createdAt: Long,
) {
    init {
        require(childrenIds.distinct().size == childrenIds.size) { "memory children must be unique" }
        require(id !in childrenIds) { "memory cannot contain itself as a child" }
        require(content.isNotBlank()) { "memory content is required" }
        require(subject.isNotBlank()) { "memory subject is required" }
        require(createdByUserId.isNotBlank()) { "createdByUserId is required" }
        MemoryAccess(visibility, allowedUserIds)
        require(evidenceRefs.isNotEmpty()) { "memory evidence is required" }
        require(createdAt >= 0) { "memory creation time must not be negative" }
    }

    fun isVisibleTo(requester: UserId): Boolean = access.isVisibleTo(requester)

    val access: MemoryAccess
        get() = MemoryAccess(visibility, allowedUserIds)
}

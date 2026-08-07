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
    val parentId: Int?,
    val createdByUserId: String,
    val content: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: MemoryCertainty,
    val visibility: MemoryVisibility,
    val evidenceRefs: List<Int>,
) {
    init {
        require(content.isNotBlank()) { "memory content is required" }
        require(subject.isNotBlank()) { "memory subject is required" }
        require(createdByUserId.isNotBlank()) { "createdByUserId is required" }
        require(evidenceRefs.isNotEmpty()) { "memory evidence is required" }
    }

    fun isVisibleTo(requester: UserId): Boolean =
        visibility.isVisibleTo(UserId(createdByUserId), requester)
}

package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryCertainty { OBSERVED, SAID, INFERRED, UNCERTAIN }

/** An approved, independently searchable household memory. */
@Serializable
data class Memory(
    val id: Int,
    val topicId: Int?,
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

    @Deprecated("Use content")
    val text: String get() = content

    @Deprecated("Use the canonical constructor")
    constructor(
        id: Int,
        text: String,
        subject: String,
        memoryType: MemoryType,
        certainty: MemoryCertainty,
        evidenceRefs: List<Int>,
    ) : this(
        id = id,
        topicId = null,
        createdByUserId = "legacy",
        content = text,
        subject = subject,
        memoryType = memoryType,
        certainty = certainty,
        visibility = MemoryVisibility.FAMILY,
        evidenceRefs = evidenceRefs,
    )
}

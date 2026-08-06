package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.memory.CandidateStatus
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlinx.serialization.Serializable

@Deprecated("Use MemoryCertainty")
typealias ClaimCertainty = MemoryCertainty

@Deprecated("Use Memory")
typealias TopicClaim = Memory

@Serializable
data class Topic(
    val id: Int,
    val createdByUserId: String,
    val sourceType: String,
    val sourceName: String,
    val title: String,
    val summary: String,
    val categories: List<String>,
    val memories: List<Memory>,
    val status: CandidateStatus,
) {
    val memoryTypes: List<MemoryType> get() = memories.map { it.memoryType }.distinct()
    val evidenceRefs: List<Int> get() = memories.flatMap { it.evidenceRefs }.distinct()

    @Deprecated("Use memories")
    val claims: List<Memory> get() = memories

    @Deprecated("Use categories")
    val domains: List<String> get() = categories

    @Deprecated("The application has one household")
    val familyId: String get() = "household"

    @Deprecated("familyId and domains are legacy names")
    constructor(
        id: Int,
        familyId: String,
        createdByUserId: String,
        sourceType: String,
        sourceName: String,
        title: String,
        summary: String,
        memoryTypes: List<MemoryType>,
        domains: List<String>,
        evidenceRefs: List<Int>,
        claims: List<Memory>,
        status: CandidateStatus,
    ) : this(
        id,
        createdByUserId,
        sourceType,
        sourceName,
        title,
        summary,
        domains,
        claims,
        status,
    )
}

@Serializable
data class ProposedMemory(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidenceRefs: List<Int>,
    val visibility: MemoryVisibility = MemoryVisibility.FAMILY,
)

@Serializable
data class ProposedTopic(
    val createdByUserId: String,
    val sourceType: String,
    val sourceName: String,
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val categories: List<String>,
    val evidenceRefs: List<Int>,
    val memories: List<ProposedMemory>,
) {
    @Deprecated("Use categories")
    val domains: List<String> get() = categories

    @Deprecated("Use memories")
    val claims: List<ProposedMemory> get() = memories

    @Deprecated("The application has one household")
    val familyId: String get() = "household"

    @Deprecated("familyId, domains, and claims are legacy proposal names")
    constructor(
        familyId: String,
        createdByUserId: String,
        sourceType: String,
        sourceName: String,
        title: String,
        summary: String,
        memoryTypes: List<MemoryType>,
        domains: List<String>,
        evidenceRefs: List<Int>,
        claims: List<ProposedMemory>,
    ) : this(
        createdByUserId,
        sourceType,
        sourceName,
        title,
        summary,
        memoryTypes,
        domains,
        evidenceRefs,
        claims,
    )
}

@Deprecated("Use ProposedMemory")
typealias TopicClaimCandidate = ProposedMemory

@Deprecated("Use ProposedTopic")
typealias TopicCandidate = ProposedTopic

package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.memory.CandidateStatus
import com.homeassistant.domain.memory.MemoryType
import kotlinx.serialization.Serializable

/** How directly the source evidence supports a claim. */
@Serializable
enum class ClaimCertainty { OBSERVED, SAID, INFERRED, UNCERTAIN }

@Serializable
data class TopicClaim(
    val id: Int,
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidenceRefs: List<Int>,
)

@Serializable
data class Topic(
    val id: Int,
    val createdByUserId: String,
    val sourceType: String,
    val sourceName: String,
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val categories: List<String>,
    val evidenceRefs: List<Int>,
    val claims: List<TopicClaim>,
    val status: CandidateStatus,
) {
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
        claims: List<TopicClaim>,
        status: CandidateStatus,
    ) : this(
        id,
        createdByUserId,
        sourceType,
        sourceName,
        title,
        summary,
        memoryTypes,
        domains,
        evidenceRefs,
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

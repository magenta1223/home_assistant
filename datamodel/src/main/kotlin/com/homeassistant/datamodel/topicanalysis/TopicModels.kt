package com.homeassistant.datamodel.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
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
    val familyId: String,
    val createdByUserId: String,
    val sourceType: String,
    val sourceName: String,
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val domains: List<String>,
    val evidenceRefs: List<Int>,
    val claims: List<TopicClaim>,
    val status: CandidateStatus,
)

@Serializable
data class TopicClaimCandidate(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidenceRefs: List<Int>,
)

@Serializable
data class TopicCandidate(
    val familyId: String,
    val createdByUserId: String,
    val sourceType: String,
    val sourceName: String,
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val domains: List<String>,
    val evidenceRefs: List<Int>,
    val claims: List<TopicClaimCandidate>,
)

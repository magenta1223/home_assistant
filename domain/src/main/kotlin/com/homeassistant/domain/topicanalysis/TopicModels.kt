package com.homeassistant.domain.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.nlp.topicanalysis.ClaimCertainty

/**
 * Evidence-backed claim restored from a topic candidate.
 *
 * @property id Candidate-local or persisted claim id.
 * @property text Claim text suitable for memory review.
 * @property subject Person, place, or concept the claim is about.
 * @property memoryType Memory category assigned to the claim.
 * @property certainty How directly source evidence supports the claim.
 * @property evidenceRefs Source references that support the claim.
 */
data class TopicClaim(
    val id: Int,
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidenceRefs: List<Int>,
)

/**
 * Pending source-agnostic topic restored from candidate storage.
 *
 * @property id Candidate id assigned by storage or preview generation.
 * @property sourceType Import source category the topic came from.
 * @property sourceName Human-readable source name or file name.
 * @property title Short topic title.
 * @property summary Review-facing topic summary.
 * @property memoryTypes Memory categories represented by the topic.
 * @property domains Normalized domain tags attached to the topic.
 * @property evidenceRefs Source references that support the topic.
 * @property claims Evidence-backed claims grouped under the topic.
 * @property status Review state for the topic candidate.
 */
data class TopicCandidate(
    val id: Int,
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

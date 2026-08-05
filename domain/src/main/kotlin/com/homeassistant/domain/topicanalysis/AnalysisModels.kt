package com.homeassistant.domain.topicanalysis

import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.source.SourceRecord

/**
 * New claim payload before persistence under a topic candidate.
 *
 * @property text Claim text suitable for memory review.
 * @property subject Person, place, or concept the claim is about.
 * @property memoryType Memory category assigned to the claim.
 * @property certainty How directly source evidence supports the claim.
 * @property evidence Source records that support the claim.
 */
data class NewTopicClaim(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidence: List<SourceRecord>,
)

/**
 * Source-agnostic topic extracted from a document before persistence.
 *
 * @property title Short topic title.
 * @property summary Review-facing topic summary.
 * @property memoryTypes Memory categories represented by the topic.
 * @property domains Normalized domain tags attached to the topic.
 * @property evidence Source records that support the topic.
 * @property claims Evidence-backed claims grouped under the topic.
 */
data class TopicDraft(
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val domains: List<String>,
    val evidence: List<SourceRecord>,
    val claims: List<NewTopicClaim>,
)

/**
 * Result of analyzing a source document into topic candidates.
 *
 * @property topics Topic candidates extracted from the source document.
 */
data class TopicAnalysisResult(val topics: List<TopicDraft>)

/** Raised when an LLM response or topic candidate violates the analysis contract. */
class TopicAnalysisException(message: String) : RuntimeException(message)

fun normalizeDomainTag(value: String): String {
    val normalized = value.trim().lowercase().replace(Regex("\\s+"), "-")
    require(normalized.isNotBlank()) { "Domain tag must not be blank" }
    return normalized
}

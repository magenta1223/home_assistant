package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import kotlinx.serialization.Serializable

/** How directly the source evidence supports a claim. */
@Serializable
enum class ClaimCertainty { OBSERVED, SAID, INFERRED, UNCERTAIN }

/**
 * One analyzable source item with prompt id, source reference, and rendered content.
 *
 * @property id Prompt-local record id used for evidence references.
 * @property ref Stable source reference returned to callers and stored as evidence.
 * @property content Rendered source content sent to the topic analyzer.
 */
data class SourceRecord(
    val id: String,
    val ref: Int,
    val content: String,
)

/**
 * Source-agnostic document passed to topic analysis.
 *
 * @property sourceType Import source category, such as kakao.
 * @property sourceName Human-readable source name or file name.
 * @property records Ordered source records available for analysis.
 */
data class SourceDocument(
    val sourceType: String,
    val sourceName: String,
    val records: List<SourceRecord>,
)

/**
 * Pending evidence-backed claim extracted under a topic candidate.
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
 * Pending source-agnostic topic extracted from a document.
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

/**
 * Result of analyzing a source document into topic candidates.
 *
 * @property topics Topic candidates extracted from the source document.
 */
data class TopicAnalysisResult(val topics: List<TopicCandidate>)

/** Raised when an LLM response or topic candidate violates the analysis contract. */
class TopicAnalysisException(message: String) : RuntimeException(message)

fun normalizeDomainTag(value: String): String {
    val normalized = value.trim().lowercase().replace(Regex("\\s+"), "-")
    require(normalized.isNotBlank()) { "Domain tag must not be blank" }
    return normalized
}

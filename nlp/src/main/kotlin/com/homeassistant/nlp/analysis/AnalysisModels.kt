package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import kotlinx.serialization.Serializable

/** How directly the source evidence supports a claim. */
@Serializable
enum class ClaimCertainty { OBSERVED, SAID, INFERRED, UNCERTAIN }

/** One analyzable source item with prompt id, source reference, and rendered content. */
data class SourceRecord(
    val id: String,
    val ref: Int,
    val content: String,
)

/** Source-agnostic document passed to topic analysis. */
data class SourceDocument(
    val sourceType: String,
    val sourceName: String,
    val records: List<SourceRecord>,
)

/** Pending evidence-backed claim extracted under a topic candidate. */
data class TopicClaim(
    val id: Int,
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidenceRefs: List<Int>,
)

/** Pending source-agnostic topic extracted from a document. */
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

/** Result of analyzing a source document into topic candidates. */
data class TopicAnalysisResult(val topics: List<TopicCandidate>)

/** Raised when an LLM response or topic candidate violates the analysis contract. */
class TopicAnalysisException(message: String) : RuntimeException(message)

fun normalizeDomainTag(value: String): String {
    val normalized = value.trim().lowercase().replace(Regex("\\s+"), "-")
    require(normalized.isNotBlank()) { "Domain tag must not be blank" }
    return normalized
}

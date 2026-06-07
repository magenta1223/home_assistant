package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import kotlinx.serialization.Serializable

/** Identifies the upstream system that supplied records for generic analysis. */
@JvmInline value class SourceType(val value: String)

/** Names a concrete source document or batch within a source type. */
@JvmInline value class SourceName(val value: String)

/** Stable record identifier used in LLM prompts and evidence references. */
@JvmInline value class SourceRecordId(val value: String)

/** Database or source-local record reference stored as topic evidence. */
@JvmInline value class SourceRecordRef(val value: Int)

/** Persistent identifier for a stored topic candidate. */
@JvmInline value class TopicCandidateId(val value: Int)

/** Persistent identifier for a stored evidence-backed claim within a topic candidate. */
@JvmInline value class TopicClaimId(val value: Int)

/** Human-readable topic title produced by analysis. */
@JvmInline value class TopicTitle(val value: String)

/** Short topic summary produced by analysis. */
@JvmInline value class TopicSummary(val value: String)

/** Atomic evidence-backed statement extracted from a source document. */
@JvmInline value class ClaimText(val value: String)

/** Person, family member, place, object, or household entity the claim is about. */
@JvmInline value class ClaimSubject(val value: String)

/** Free-form normalized domain tag attached to an analyzed topic. */
@JvmInline value class DomainTag(val value: String)

/** How directly the source evidence supports a claim. */
@Serializable
enum class ClaimCertainty { OBSERVED, SAID, INFERRED, UNCERTAIN }

/** One analyzable source item with prompt id, source reference, and rendered content. */
data class SourceRecord(
    val id: SourceRecordId,
    val ref: SourceRecordRef,
    val content: String,
)

/** Source-agnostic document passed to topic analysis. */
data class SourceDocument(
    val sourceType: SourceType,
    val sourceName: SourceName,
    val records: List<SourceRecord>,
)

/** Pending evidence-backed claim extracted under a topic candidate. */
data class TopicClaim(
    val id: TopicClaimId,
    val text: ClaimText,
    val subject: ClaimSubject,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidenceRefs: List<SourceRecordRef>,
)

/** Pending source-agnostic topic extracted from a document. */
data class TopicCandidate(
    val id: TopicCandidateId,
    val sourceType: SourceType,
    val sourceName: SourceName,
    val title: TopicTitle,
    val summary: TopicSummary,
    val memoryTypes: List<MemoryType>,
    val domains: List<DomainTag>,
    val evidenceRefs: List<SourceRecordRef>,
    val claims: List<TopicClaim>,
    val status: CandidateStatus,
)

/** Result of analyzing a source document into topic candidates. */
data class TopicAnalysisResult(val topics: List<TopicCandidate>)

/** Raised when an LLM response or topic candidate violates the analysis contract. */
class TopicAnalysisException(message: String) : RuntimeException(message)

fun normalizeDomainTag(value: String): DomainTag {
    val normalized = value.trim().lowercase().replace(Regex("\\s+"), "-")
    require(normalized.isNotBlank()) { "Domain tag must not be blank" }
    return DomainTag(normalized)
}

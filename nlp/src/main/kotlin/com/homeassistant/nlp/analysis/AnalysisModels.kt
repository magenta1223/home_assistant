package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType

@JvmInline value class SourceType(val value: String)
@JvmInline value class SourceName(val value: String)
@JvmInline value class SourceRecordId(val value: String)
@JvmInline value class SourceRecordRef(val value: Int)
@JvmInline value class TopicCandidateId(val value: Int)
@JvmInline value class TopicTitle(val value: String)
@JvmInline value class TopicSummary(val value: String)
@JvmInline value class DomainTag(val value: String)

data class SourceRecord(
    val id: SourceRecordId,
    val ref: SourceRecordRef,
    val content: String,
)

data class SourceDocument(
    val sourceType: SourceType,
    val sourceName: SourceName,
    val records: List<SourceRecord>,
)

data class TopicCandidate(
    val id: TopicCandidateId,
    val sourceType: SourceType,
    val sourceName: SourceName,
    val title: TopicTitle,
    val summary: TopicSummary,
    val memoryTypes: List<MemoryType>,
    val domains: List<DomainTag>,
    val evidenceRefs: List<SourceRecordRef>,
    val status: CandidateStatus,
)

data class TopicAnalysisResult(val topics: List<TopicCandidate>)

class TopicAnalysisException(message: String) : RuntimeException(message)

fun normalizeDomainTag(value: String): DomainTag {
    val normalized = value.trim().lowercase().replace(Regex("\\s+"), "-")
    require(normalized.isNotBlank()) { "Domain tag must not be blank" }
    return DomainTag(normalized)
}

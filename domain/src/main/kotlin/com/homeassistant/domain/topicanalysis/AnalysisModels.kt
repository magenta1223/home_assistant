package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.source.SourceRecord

/**
 * New canonical-memory proposal before persistence under a topic.
 *
 * @property text Memory content suitable for review.
 * @property subject Person, place, or concept the memory is about.
 * @property memoryType Category assigned to the memory.
 * @property certainty How directly source evidence supports the memory.
 * @property evidence Source records that support the memory.
 */
data class NewMemory(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: MemoryCertainty,
    val evidence: List<SourceRecord>,
)

/**
 * Source-agnostic topic extracted from a document before persistence.
 *
 * @property title Short topic title.
 * @property summary Review-facing topic summary.
 * @property memoryTypes Memory categories represented by the topic.
 * @property categories Normalized category tags attached to the topic.
 * @property evidence Source records that support the topic.
 * @property memories Evidence-backed memories grouped under the topic.
 */
data class TopicDraft(
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val categories: List<String>,
    val evidence: List<SourceRecord>,
    val memories: List<NewMemory>,
)

/**
 * Result of analyzing a source document into proposed topics.
 *
 * @property topics Proposed topics extracted from the source document.
 */
data class TopicAnalysisResult(val topics: List<TopicDraft>)

/** Raised when an LLM response or proposed topic violates the analysis contract. */
class TopicAnalysisException(message: String) : RuntimeException(message)

fun normalizeCategory(value: String): String {
    val normalized = value.trim().lowercase().replace(Regex("\\s+"), "-")
    require(normalized.isNotBlank()) { "Category must not be blank" }
    return normalized
}

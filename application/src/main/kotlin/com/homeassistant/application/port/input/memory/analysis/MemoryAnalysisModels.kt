package com.homeassistant.application.port.input.memory.analysis

import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDocumentDraft
import kotlinx.serialization.Serializable

data class MemoryAnalysisRequest(
    val userId: String,
    val source: SourceDocumentDraft,
)

@Serializable
data class MemoryAnalysisResult(
    val sourceType: String,
    val sourceName: String,
    val importedRecordCount: Int,
    val retriedRecordCount: Int,
    val alreadyAnalyzedRecordCount: Int,
    val publicMemoryCount: Int,
    val privateMemoryCount: Int,
    val memories: List<MemoryProposal>,
)

class DuplicateSourceRecordsException internal constructor(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount source records already exist: $sourceName")

class MemoryAnalysisUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("memory analysis is unavailable", cause)

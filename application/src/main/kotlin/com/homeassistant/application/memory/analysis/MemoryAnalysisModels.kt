package com.homeassistant.application.memory.analysis

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
    val publicMemoryCount: Int,
    val privateMemoryCount: Int,
    val memories: List<MemoryProposal>,
)

class DuplicateSourceRecordsException(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount source records already exist: $sourceName")

package com.homeassistant.application.port.input.memory.analysis

import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDocumentDraft
import kotlinx.serialization.Serializable

data class MemoryAnalysisRequest(
    val userId: String,
    val source: SourceDocumentDraft,
    val access: MemoryAccess = MemoryAccess.PUBLIC,
)

@Serializable
data class MemoryAnalysisResult(
    val sourceType: String,
    val sourceName: String,
    val importedRecordCount: Int,
    val retriedRecordCount: Int,
    val alreadyAnalyzedRecordCount: Int,
    val visibility: MemoryVisibility,
    val allowedUserIds: Set<String>,
    val memoryCount: Int,
    val memories: List<MemoryProposal>,
)

class DuplicateSourceRecordsException internal constructor(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount source records already exist: $sourceName")

class MemoryAnalysisUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("memory analysis is unavailable", cause)

class InvalidMemoryAudienceException internal constructor(
    val userIds: Set<String>,
) : RuntimeException("memory audience contains unknown or unauthorized users")

class ConflictingSourceAudienceException internal constructor(
    val sourceName: String,
    val existingAccess: MemoryAccess,
) : RuntimeException("source already exists with a different audience: $sourceName")

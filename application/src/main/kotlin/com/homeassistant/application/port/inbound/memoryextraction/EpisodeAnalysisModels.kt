package com.homeassistant.application.port.inbound.memoryextraction

import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.source.SourceDocumentDraft

data class EpisodeAnalysisRequest(
    val userId: String,
    val source: SourceDocumentDraft,
    val access: EpisodeAccess = EpisodeAccess.PUBLIC,
)

data class EpisodeAnalysisResult(
    val sourceType: String,
    val sourceName: String,
    val importedRecordCount: Int,
    val retriedRecordCount: Int,
    val alreadyAnalyzedRecordCount: Int,
    val visibility: EpisodeVisibility,
    val allowedUserIds: Set<String>,
    val episodeCount: Int,
    val episodes: List<EpisodeProposal>,
)

class DuplicateSourceRecordsException internal constructor(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount source records already exist: $sourceName")

class EpisodeAnalysisUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("episode analysis is unavailable", cause)

class InvalidEpisodeAudienceException internal constructor(
    val userIds: Set<String>,
) : RuntimeException("episode audience contains unknown or unauthorized users")

class InvalidKnowledgeReferenceException internal constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ConflictingSourceAudienceException internal constructor(
    val sourceName: String,
    val existingAccess: EpisodeAccess,
) : RuntimeException("source already exists with a different audience: $sourceName")


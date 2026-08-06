package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.topicanalysis.TopicProposal
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnalysisRequest(
    val userId: String,
    val sourceType: String,
    val sourceName: String,
    val text: String,
)

@Serializable
data class TopicAnalysisResult(
    val previewId: String,
    val sourceType: String,
    val sourceName: String,
    val importedRecordCount: Int,
    val topics: List<TopicProposal>,
)

class DuplicateSourceRecordsException(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount source records already exist: $sourceName")

class TopicAnalysisException(message: String) : RuntimeException(message)

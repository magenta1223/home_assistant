package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.topicanalysis.ProposedTopic
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
    val topics: List<ProposedTopic>,
)

class DuplicateSourceRecordsException(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount source records already exist: $sourceName")

package com.homeassistant.nlp.topicanalysis.api

import com.homeassistant.domain.topicanalysis.NewTopicCandidate
import com.homeassistant.domain.topicanalysis.TopicCandidate
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnalysisRequest(
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
    val topics: List<NewTopicCandidate>,
)

@Serializable
data class TopicAnalysisSaveRequest(
    val previewId: String
)

@Serializable
data class TopicAnalysisSaveResult(
    val previewId: String,
    val topics: List<TopicCandidate>,
)


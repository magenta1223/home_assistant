package com.homeassistant.nlp.topicanalysis.api

import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicCandidate
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnalysisRequest(
    val userId: String,
    val familyId: String,
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
    val topics: List<TopicCandidate>,
)

@Serializable
data class TopicAnalysisSaveRequest(
    val previewId: String,
    val userId: String,
    val familyId: String,
)

@Serializable
data class TopicAnalysisSaveResult(
    val previewId: String,
    val topics: List<Topic>,
)

@Serializable
data class TopicAnalysisSelectionSaveRequest(
    val previewId: String,
    val userId: String,
    val familyId: String,
    val selectedTopicIndices: Set<Int>,
)


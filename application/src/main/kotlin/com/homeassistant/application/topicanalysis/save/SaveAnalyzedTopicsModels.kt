package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.topicanalysis.Topic
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnalysisSaveRequest(
    val previewId: String,
    val userId: String,
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
    val selectedTopicIndices: Set<Int>,
)

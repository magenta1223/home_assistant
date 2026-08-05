package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.topicanalysis.Topic
import kotlinx.serialization.Serializable

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

class TopicAnalysisPreviewNotFoundException(
    val previewId: String,
) : RuntimeException("Topic analysis preview not found: $previewId")

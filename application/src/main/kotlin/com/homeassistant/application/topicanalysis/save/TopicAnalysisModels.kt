package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.topicanalysis.Topic
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnalysisSaveRequest(
    val previewId: String,
    val userId: String,
) {
    @Deprecated("familyId is ignored because the application has one household")
    constructor(previewId: String, userId: String, familyId: String) : this(previewId, userId)

    @Deprecated("The application has one household")
    val familyId: String get() = "household"
}

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
) {
    @Deprecated("familyId is ignored because the application has one household")
    constructor(
        previewId: String,
        userId: String,
        familyId: String,
        selectedTopicIndices: Set<Int>,
    ) : this(previewId, userId, selectedTopicIndices)

    @Deprecated("The application has one household")
    val familyId: String get() = "household"
}

class TopicAnalysisPreviewNotFoundException(
    val previewId: String,
) : RuntimeException("Topic analysis preview not found: $previewId")

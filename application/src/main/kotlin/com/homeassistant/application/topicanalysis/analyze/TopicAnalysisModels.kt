package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.topicanalysis.ProposedTopic
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnalysisRequest(
    val userId: String,
    val sourceType: String,
    val sourceName: String,
    val text: String,
) {
    @Deprecated("familyId is ignored because the application has one household")
    constructor(
        userId: String,
        familyId: String,
        sourceType: String,
        sourceName: String,
        text: String,
    ) : this(userId, sourceType, sourceName, text)

    @Deprecated("The application has one household")
    val familyId: String get() = "household"
}

@Serializable
data class TopicAnalysisResult(
    val previewId: String,
    val sourceType: String,
    val sourceName: String,
    val importedRecordCount: Int,
    val topics: List<ProposedTopic>,
)

class DuplicateKakaoMessagesException(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount Kakao messages already exist: $sourceName")

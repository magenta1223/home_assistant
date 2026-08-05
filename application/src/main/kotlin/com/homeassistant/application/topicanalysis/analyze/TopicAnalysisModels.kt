package com.homeassistant.application.topicanalysis.analyze

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

class DuplicateKakaoMessagesException(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount Kakao messages already exist: $sourceName")

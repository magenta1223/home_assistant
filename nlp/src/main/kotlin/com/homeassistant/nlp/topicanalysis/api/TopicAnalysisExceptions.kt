package com.homeassistant.nlp.topicanalysis.api

class TopicAnalysisPreviewNotFoundException(
    val previewId: String,
) : RuntimeException("Topic analysis preview not found: $previewId")

class DuplicateKakaoMessagesException(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount Kakao messages already exist: $sourceName")

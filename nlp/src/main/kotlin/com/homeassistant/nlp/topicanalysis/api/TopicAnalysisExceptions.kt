package com.homeassistant.nlp.topicanalysis.api

class TopicAnalysisPreviewNotFoundException(
    val previewId: String,
) : RuntimeException("Topic analysis preview not found: $previewId")

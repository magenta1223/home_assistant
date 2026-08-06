package com.homeassistant.domain.topicanalysis

data class TopicAnalysisPreview(
    val previewId: String,
    val sourceName: String,
    val text: String,
    val topics: List<ProposedTopic>,
)

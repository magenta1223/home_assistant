package com.homeassistant.domain.topicanalysis

data class TopicAnalysisPreview(
    val previewId: String,
    val requestedByUserId: String,
    val sourceType: String,
    val sourceName: String,
    val topics: List<TopicProposal>,
)

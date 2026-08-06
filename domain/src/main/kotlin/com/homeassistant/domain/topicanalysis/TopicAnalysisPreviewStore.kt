package com.homeassistant.domain.topicanalysis

interface TopicAnalysisPreviewStore {
    fun createPreview(
        requestedByUserId: String,
        sourceType: String,
        sourceName: String,
        topics: List<TopicProposal>,
    ): TopicAnalysisPreview

    fun findPreview(previewId: String): TopicAnalysisPreview?
}

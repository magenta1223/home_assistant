package com.homeassistant.domain.topicanalysis

interface TopicAnalysisPreviewStore {
    fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<ProposedTopic>,
    ): TopicAnalysisPreview

    fun findPreview(previewId: String): TopicAnalysisPreview?
}

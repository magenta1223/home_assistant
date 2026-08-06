package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.kakao.KakaoAnalysisPreview

interface TopicAnalysisPreviewStore {
    fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<ProposedTopic>,
    ): KakaoAnalysisPreview

    fun findPreview(previewId: String): KakaoAnalysisPreview?
}

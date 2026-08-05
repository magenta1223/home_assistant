package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.kakao.KakaoAnalysisPreview

interface TopicAnalysisPreviewStore {
    fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<TopicCandidate>,
    ): KakaoAnalysisPreview

    fun findPreview(previewId: String): KakaoAnalysisPreview?
}

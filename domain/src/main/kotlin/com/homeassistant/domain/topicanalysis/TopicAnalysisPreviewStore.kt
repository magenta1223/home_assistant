package com.homeassistant.domain.topicanalysis

import com.homeassistant.datamodel.kakao.KakaoAnalysisPreview
import com.homeassistant.datamodel.topicanalysis.TopicCandidate

interface TopicAnalysisPreviewStore {
    fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<TopicCandidate>,
    ): KakaoAnalysisPreview

    fun findPreview(previewId: String): KakaoAnalysisPreview?
}

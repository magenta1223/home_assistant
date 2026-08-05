package com.homeassistant.domain.kakao

import com.homeassistant.domain.topicanalysis.TopicCandidate

/**
 * KakaoTalk message row stored in the local import database.
 */
data class KakaoMessage(
    val id: Int,
    val sourceFileName: String,
    val sender: String,
    val displayTime: String,
    val text: String,
    val lineStart: Int,
    val lineEnd: Int,
    val fingerprint: String,
)

data class KakaoAnalysisPreview(
    val previewId: String,
    val sourceFileName: String,
    val text: String,
    val topics: List<TopicCandidate>,
)

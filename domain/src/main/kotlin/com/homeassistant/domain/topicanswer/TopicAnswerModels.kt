package com.homeassistant.domain.topicanswer

import kotlinx.serialization.Serializable

@Serializable
data class TopicAnswerRequest(
    val question: String,
    val limit: Int = 5,
)

@Serializable
data class TopicAnswerResult(
    val question: String,
    val answer: String,
    val matches: List<TopicAnswerMatch>,
)

@Serializable
data class TopicAnswerMatch(
    val topicId: Int,
    val title: String,
    val summary: String,
    val claims: List<String>,
    val evidenceRefs: List<Int>,
)

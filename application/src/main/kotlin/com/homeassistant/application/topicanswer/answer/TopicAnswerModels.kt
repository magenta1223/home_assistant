package com.homeassistant.application.topicanswer.answer

import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnswerRequest(
    val userId: String,
    val question: String,
    val limit: Int = 5,
) {
    fun requester(): UserId = UserId(userId)

    @Deprecated("familyId is ignored because the application has one household")
    constructor(userId: String, familyId: String, question: String, limit: Int = 5) :
        this(userId, question, limit)
}

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

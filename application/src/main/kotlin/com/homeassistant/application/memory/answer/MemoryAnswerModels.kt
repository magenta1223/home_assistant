package com.homeassistant.application.memory.answer

import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable

@Serializable
data class MemoryAnswerRequest(
    val userId: String,
    val question: String,
    val limit: Int = 5,
) {
    fun requester(): UserId = UserId(userId)
}

@Serializable
data class MemoryAnswerResult(
    val question: String,
    val answer: String,
    val matches: List<MemoryAnswerMatch>,
)

@Serializable
data class MemoryAnswerMatch(
    val memoryId: Int,
    val topicId: Int,
    val topicTitle: String,
    val topicSummary: String,
    val content: String,
    val evidenceRefs: List<Int>,
)

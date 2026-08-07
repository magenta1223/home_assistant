package com.homeassistant.application.memory.answer

import com.homeassistant.application.memory.io.MemorySearchMatch
import kotlinx.serialization.Serializable

@Serializable
data class MemoryAnswerRequest(
    val userId: String,
    val question: String,
    val limit: Int = 5,
)

@Serializable
data class MemoryAnswerResult(
    val question: String,
    val answer: String,
    val matches: List<MemorySearchMatch>,
)

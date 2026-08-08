package com.homeassistant.application.port.input.memory.answer

import com.homeassistant.application.port.input.memory.search.MemorySearchMatch
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

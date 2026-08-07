package com.homeassistant.application.memory.memorygroundedchat

import com.homeassistant.application.memory.read.MemorySearchMatch
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

package com.homeassistant.application.port.input.topic

import com.homeassistant.domain.identity.UserId

/** Finds the Topics nearest to a question, with only the memories the requester can read. */
fun interface SearchTopics {
    fun search(userId: UserId, query: String): List<TopicMatch>
}

/** [memories] are newest first and never empty; a Topic with nothing readable is not a match. */
data class TopicMatch(val topicId: Int, val score: Double, val memories: List<TopicMatchMemory>) {
    init { require(memories.isNotEmpty()) }
}

data class TopicMatchMemory(val memoryId: Int, val content: String, val createdAt: Long)

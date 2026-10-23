package com.homeassistant.application.port.input.topic

import com.homeassistant.domain.identity.UserId

/** Finds the Topics that best cover a question, with only the memories the requester can read. */
fun interface SearchTopics {
    /**
     * The Topics covering [query] for [userId] in backbone pick order; empty when no Topic exists or none is readable.
     * @throws com.homeassistant.domain.identity.UserAccessDeniedException when [userId] may not search.
     */
    fun search(userId: UserId, query: String): List<TopicMatch>
}

/**
 * [memories] are most relevant to the question first and never empty; a memory appears under one match only, and a
 * Topic with nothing readable left is not a match.
 */
data class TopicMatch(val topicId: Int, val score: Double, val memories: List<TopicMatchMemory>) {
    init { require(memories.isNotEmpty()) }
}

data class TopicMatchMemory(val memoryId: Int, val content: String, val createdAt: Long)

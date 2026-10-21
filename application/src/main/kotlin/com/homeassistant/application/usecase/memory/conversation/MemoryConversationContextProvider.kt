package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.topic.SearchTopics
import com.homeassistant.application.port.input.topic.TopicMatch
import com.homeassistant.domain.identity.UserId
import java.time.Instant

fun interface MemoryConversationContextSource {
    fun context(userId: UserId, question: String): MemoryConversationContext
}

/** The reference holds one block per matched Topic, with only the memories the user can read. */
class MemoryConversationContextProvider(
    private val topicSearch: SearchTopics,
) : MemoryConversationContextSource {
    override fun context(userId: UserId, question: String): MemoryConversationContext {
        val matches = topicSearch.search(userId, question)
        return MemoryConversationContext(
            reference = matches.joinToString("\n", transform = ::topicReferenceBlock).take(MAX_CONTEXT_CHARS),
            hasMatches = matches.isNotEmpty(),
        )
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
    }
}

internal fun topicReferenceBlock(match: TopicMatch): String = buildString {
    appendLine("## Topic ${match.topicId} [score=${match.score}]")
    match.memories.forEach { appendLine("- [savedAt=${Instant.ofEpochMilli(it.createdAt)}] ${it.content}") }
}.trimEnd()

data class MemoryConversationContext(
    val reference: String,
    val hasMatches: Boolean,
)

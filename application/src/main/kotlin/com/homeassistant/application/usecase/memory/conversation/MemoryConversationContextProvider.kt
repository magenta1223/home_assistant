package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.memory.search.MemorySearchMatch
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.application.usecase.memory.answer.MemoryAnswerContextProvider
import com.homeassistant.domain.identity.UserId
import java.time.Instant

fun interface MemoryConversationContextSource {
    fun context(userId: UserId, question: String): MemoryConversationContext
}

class MemoryConversationContextProvider(
    private val answerContext: MemoryAnswerContextProvider,
) : MemoryConversationContextSource {
    override fun context(userId: UserId, question: String): MemoryConversationContext {
        val result = answerContext.context(
            SearchMemoriesRequest(
                userId = userId.value,
                query = question,
                limit = MAX_MATCHES,
            ),
        )
        return MemoryConversationContext(
            reference = result.contextMatches.joinToString("\n", transform = ::memoryReferenceLine)
                .take(MAX_CONTEXT_CHARS),
            hasMatches = result.directMatches.isNotEmpty(),
        )
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
        const val MAX_MATCHES = 5
    }
}

internal fun memoryReferenceLine(match: MemorySearchMatch): String =
    "- [savedAt=${Instant.ofEpochMilli(match.createdAt)}; source=${match.source}; " +
        "score=${match.score}; parentMemoryId=${match.parentMemoryId ?: "none"}; depth=${match.depth}] ${match.content}"

data class MemoryConversationContext(
    val reference: String,
    val hasMatches: Boolean,
)

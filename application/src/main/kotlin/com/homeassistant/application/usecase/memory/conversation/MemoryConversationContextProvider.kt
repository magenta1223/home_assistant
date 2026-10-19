package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.memory.search.MemorySearch
import com.homeassistant.application.port.input.memory.search.MemorySearchMatch
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.domain.identity.UserId
import java.time.Instant

fun interface MemoryConversationContextSource {
    fun context(userId: UserId, question: String): MemoryConversationContext
}

class MemoryConversationContextProvider(
    private val memorySearch: MemorySearch,
) : MemoryConversationContextSource {
    override fun context(userId: UserId, question: String): MemoryConversationContext {
        val result = memorySearch.search(
            SearchMemoriesRequest(
                userId = userId.value,
                query = question,
                limit = MAX_MATCHES,
            ),
        )
        return MemoryConversationContext(
            reference = result.matches.joinToString("\n", transform = ::memoryReferenceLine)
                .take(MAX_CONTEXT_CHARS),
            hasMatches = result.matches.isNotEmpty(),
        )
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
        const val MAX_MATCHES = 5
    }
}

internal fun memoryReferenceLine(match: MemorySearchMatch): String =
    "- [savedAt=${Instant.ofEpochMilli(match.createdAt)}; score=${match.score}] ${match.content}"

data class MemoryConversationContext(
    val reference: String,
    val hasMatches: Boolean,
)

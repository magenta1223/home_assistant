package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.topic.SearchTopics
import com.homeassistant.application.port.input.topic.TopicMatch
import com.homeassistant.domain.identity.UserId
import java.time.Instant

/** Supplies the memory reference a conversation answers from. */
fun interface MemoryConversationContextSource {
    /** The memories [userId] can read that relate to [question], as a reference block. */
    fun context(userId: UserId, question: String): MemoryConversationContext
}

/**
 * The reference holds one block per matched Topic, with only the memories the user can read. Whole lines are kept in
 * search order up to [MAX_CONTEXT_CHARS], and a Topic header is never left without a memory under it.
 */
class MemoryConversationContextProvider(
    private val topicSearch: SearchTopics,
) : MemoryConversationContextSource {
    override fun context(userId: UserId, question: String): MemoryConversationContext {
        val reference = withinBudget(topicSearch.search(userId, question).flatMap(::topicReferenceLines)).joinToString("\n")
        return MemoryConversationContext(reference = reference, hasMatches = reference.isNotEmpty())
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
    }
}

internal fun topicReferenceLines(match: TopicMatch): List<String> =
    listOf("## Topic ${match.topicId} [score=${match.score}]") + match.memories.map { "- [savedAt=${Instant.ofEpochMilli(it.createdAt)}] ${it.content}" }

/** The leading lines whose newline-joined length fits the budget, without a trailing header. */
private fun withinBudget(lines: List<String>): List<String> {
    val joinedLengths = lines.runningFold(-1) { length, line -> length + 1 + line.length }.drop(1)
    return lines.take(joinedLengths.count { it <= MemoryConversationContextProvider.MAX_CONTEXT_CHARS })
        .dropLastWhile { it.startsWith("## Topic ") }
}

/** [reference] is the text given to the provider; [hasMatches] is false when it holds no memory. */
data class MemoryConversationContext(
    val reference: String,
    val hasMatches: Boolean,
)

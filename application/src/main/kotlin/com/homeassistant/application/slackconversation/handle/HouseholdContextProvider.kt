package com.homeassistant.application.slackconversation.handle

import com.homeassistant.application.memory.memorygroundedchat.MemoryAnswerContextProvider
import com.homeassistant.application.memory.read.MemorySearchMatch
import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.application.memory.read.SearchMemoriesRequest
import java.time.Instant

/** Builds a bounded household-memory context for a Slack question. */
interface HouseholdContextSource {
    /** Builds household-memory context relevant to a question. */
    fun context(principal: SlackPrincipal, question: String): HouseholdContext
}

class HouseholdContextProvider(
    private val answerContext: MemoryAnswerContextProvider,
) : HouseholdContextSource {
    override fun context(principal: SlackPrincipal, question: String): HouseholdContext {
        val result = answerContext.context(
            SearchMemoriesRequest(
                userId = principal.userId.value,
                query = question,
                limit = MAX_MATCHES,
            ),
        )
        return HouseholdContext(
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
    "- [savedAt=${Instant.ofEpochMilli(match.createdAt)}; source=${match.source}; " +
        "score=${match.score}; parentMemoryId=${match.parentMemoryId ?: "none"}; depth=${match.depth}] ${match.content}"

data class HouseholdContext(
    val reference: String,
    val hasMatches: Boolean,
)

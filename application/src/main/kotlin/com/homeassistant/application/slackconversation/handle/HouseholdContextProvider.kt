package com.homeassistant.application.slackconversation.handle

import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.application.memory.search.SearchMemoriesRequest
import com.homeassistant.application.memory.search.SearchMemoriesUseCase

/** Builds a bounded household-memory context for a Slack question. */
interface HouseholdContextSource {
    /** Builds household-memory context relevant to a question. */
    fun context(principal: SlackPrincipal, question: String): HouseholdContext
}

class HouseholdContextProvider(
    private val searchMemories: SearchMemoriesUseCase,
) : HouseholdContextSource {
    override fun context(principal: SlackPrincipal, question: String): HouseholdContext {
        val result = searchMemories.search(
            SearchMemoriesRequest(
                userId = principal.userId.value,
                query = question,
                limit = MAX_MATCHES,
            ),
        )
        return HouseholdContext(
            reference = result.matches.joinToString("\n") { match ->
                buildString {
                    append("- ")
                    match.topicTitle?.let {
                        append(it)
                        append(": ")
                    }
                    append(match.content)
                }
            }.take(MAX_CONTEXT_CHARS),
            hasMatches = result.matches.isNotEmpty(),
        )
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
        const val MAX_MATCHES = 5
    }
}

data class HouseholdContext(
    val reference: String,
    val hasMatches: Boolean,
)

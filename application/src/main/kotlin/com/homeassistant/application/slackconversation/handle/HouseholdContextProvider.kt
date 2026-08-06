package com.homeassistant.application.slackconversation.handle

import com.homeassistant.domain.slackconversation.SlackPrincipal
import com.homeassistant.application.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.memory.answer.MemoryAnswerUseCase

interface HouseholdContextSource {
    fun context(principal: SlackPrincipal, question: String): HouseholdContext
}

class HouseholdContextProvider(
    private val memoryAnswer: MemoryAnswerUseCase,
) : HouseholdContextSource {
    override fun context(principal: SlackPrincipal, question: String): HouseholdContext {
        val result = memoryAnswer.answer(
            MemoryAnswerRequest(
                userId = principal.userId.value,
                question = question,
                limit = MAX_MATCHES,
            ),
        )
        return HouseholdContext(
            reference = result.matches.joinToString("\n") { match ->
                buildString {
                    append("- ")
                    append(match.topicTitle)
                    append(": ")
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

package com.homeassistant.application.slackconversation.handle

import com.homeassistant.domain.slackconversation.SlackPrincipal
import com.homeassistant.application.topicanswer.answer.TopicAnswerRequest
import com.homeassistant.application.topicanswer.answer.TopicAnswerUseCase

interface HouseholdContextSource {
    fun context(principal: SlackPrincipal, question: String): HouseholdContext
}

class HouseholdContextProvider(
    private val topicAnswer: TopicAnswerUseCase,
) : HouseholdContextSource {
    override fun context(principal: SlackPrincipal, question: String): HouseholdContext {
        val result = topicAnswer.answer(
            TopicAnswerRequest(
                userId = principal.userId.value,
                familyId = principal.familyId.value,
                question = question,
                limit = MAX_MATCHES,
            ),
        )
        return HouseholdContext(
            reference = result.matches.joinToString("\n") { match ->
                buildString {
                    append("- ")
                    append(match.title)
                    append(": ")
                    append(match.summary)
                    if (match.claims.isNotEmpty()) {
                        append(" | ")
                        append(match.claims.take(MAX_CLAIMS_PER_MATCH).joinToString(" "))
                    }
                }
            }.take(MAX_CONTEXT_CHARS),
            hasMatches = result.matches.isNotEmpty(),
        )
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
        const val MAX_MATCHES = 5
        const val MAX_CLAIMS_PER_MATCH = 3
    }
}

data class HouseholdContext(
    val reference: String,
    val hasMatches: Boolean,
)

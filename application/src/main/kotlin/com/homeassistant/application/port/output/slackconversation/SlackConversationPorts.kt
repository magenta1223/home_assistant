package com.homeassistant.application.port.output.slackconversation

import com.homeassistant.application.port.input.slackconversation.SlackPrincipal

/** Represents the outcome of one conversation turn. */
sealed interface ConversationTurnResult {
    data class Success(val answer: String) : ConversationTurnResult
    data class Failure(val category: String) : ConversationTurnResult
}

/** Resolves an incoming Slack actor to an application principal. */
fun interface SlackPrincipalResolver {
    fun resolve(teamId: String?, slackUserId: String?): SlackPrincipal?
}

/** Starts or resumes a short-lived conversation turn. */
interface ConversationTurnClient {
    fun start(prompt: String, onThreadStarted: (String) -> Unit): ConversationTurnResult
    fun resume(threadId: String, prompt: String): ConversationTurnResult
}

/** Delivers a generated answer or retryable failure back to Slack. */
interface ConversationAnswerPublisher {
    fun postAnswer(channelId: String, answer: String): String
    fun postRetryableError(channelId: String)
}

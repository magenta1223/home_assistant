package com.homeassistant.application.usecase.slackconversation

import com.homeassistant.application.port.input.slackconversation.SlackConversationHandler
import com.homeassistant.application.port.input.slackconversation.SlackConversationMessage
import com.homeassistant.application.port.output.slackconversation.ConversationAnswerPublisher
import com.homeassistant.application.port.output.slackconversation.ConversationTurnClient
import com.homeassistant.application.port.output.slackconversation.ConversationTurnResult
import com.homeassistant.application.port.output.slackconversation.SlackConversationSession
import com.homeassistant.application.port.output.slackconversation.SlackConversationSessionStore
import com.homeassistant.application.port.output.slackconversation.SlackMessageKey
import com.homeassistant.application.port.output.slackconversation.SlackMessageReceiptStatus
import com.homeassistant.application.port.output.slackconversation.SlackPrincipalResolver
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

class HandleSlackConversation(
    private val identities: SlackPrincipalResolver,
    private val sessions: SlackConversationSessionStore,
    private val contextProvider: HouseholdContextSource,
    private val conversationClient: ConversationTurnClient,
    private val answerPublisher: ConversationAnswerPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val promptBuilder: ConversationPromptBuilder = ConversationPromptBuilder(),
) : SlackConversationHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(message: SlackConversationMessage) {
        val principal = identities.resolve(message.teamId, message.slackUserId) ?: return
        val key = SlackMessageKey(message.channelId, message.messageTs)
        val claimed = sessions.claimMessage(key, now())
        if (claimed == null) {
            redeliverAnswerReady(key)
            return
        }

        val active = sessions.active(principal, now(), SESSION_IDLE_TIMEOUT_MILLIS)
        val context = try {
            contextProvider.context(principal, message.text)
        } catch (error: Exception) {
            log.warn("Household context retrieval failed category={}", error.javaClass.simpleName)
            sessions.markFailed(key, now())
            answerPublisher.postRetryableError(message.channelId)
            return
        }
        if (!context.hasMatches) {
            sessions.markAnswerReady(key, NO_MATCH_ANSWER, now())
            deliverStoredAnswer(key, message.channelId, NO_MATCH_ANSWER)
            return
        }
        val prompt = promptBuilder.build(context.reference, message.text)
        val startedSession = AtomicReference<SlackConversationSession>()
        val result = if (active == null) {
            conversationClient.start(prompt) { threadId ->
                val session = sessions.createAndActivate(principal, threadId, now())
                sessions.attachSession(key, session.id, now())
                startedSession.set(session)
            }
        } else {
            conversationClient.resume(active.conversationThreadId, prompt)
        }

        when (result) {
            is ConversationTurnResult.Failure -> {
                sessions.markFailed(key, now())
                sessions.clearActive(principal)
                answerPublisher.postRetryableError(message.channelId)
            }
            is ConversationTurnResult.Success -> {
                val session = active ?: startedSession.get()
                if (session == null) {
                    sessions.markFailed(key, now())
                    sessions.clearActive(principal)
                    answerPublisher.postRetryableError(message.channelId)
                    return
                }
                sessions.touch(principal, session.id, now())
                sessions.markAnswerReady(key, result.answer, now())
                deliverStoredAnswer(key, message.channelId, result.answer)
            }
        }
    }

    private fun redeliverAnswerReady(key: SlackMessageKey) {
        val receipt = sessions.receipt(key) ?: return
        if (receipt.status != SlackMessageReceiptStatus.ANSWER_READY) return
        val answer = receipt.answerText?.takeIf { it.isNotBlank() } ?: return
        deliverStoredAnswer(key, key.channelId, answer)
    }

    private fun deliverStoredAnswer(
        key: SlackMessageKey,
        channelId: String,
        answer: String,
    ) {
        runCatching { answerPublisher.postAnswer(channelId, answer) }
            .onSuccess { responseTs -> sessions.markCompleted(key, responseTs, now()) }
            .onFailure { error ->
                log.warn("Slack answer delivery failed category={}", error.javaClass.simpleName)
            }
    }

    private fun now(): Long = clock.millis()

    companion object {
        const val SESSION_IDLE_TIMEOUT_MILLIS = 600_000L
        const val NO_MATCH_ANSWER = "저장된 기억에서 관련 내용을 찾지 못했습니다."
    }
}

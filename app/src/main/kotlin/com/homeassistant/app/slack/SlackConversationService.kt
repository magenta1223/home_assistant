package com.homeassistant.app.slack

import com.homeassistant.domain.slackconversation.SlackCodexSession
import com.homeassistant.domain.slackconversation.SlackCodexSessionStore
import com.homeassistant.domain.slackconversation.SlackMessageKey
import com.homeassistant.domain.slackconversation.SlackMessageReceiptStatus
import com.homeassistant.domain.slackconversation.SlackPrincipal
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

data class SlackConversationMessage(
    val teamId: String,
    val slackUserId: String,
    val channelId: String,
    val messageTs: String,
    val text: String,
)

interface SlackConversationHandler {
    fun submit(message: SlackConversationMessage)
    fun handle(message: SlackConversationMessage)
}
internal class SlackConversationService(
    private val identities: SlackIdentityDirectory,
    private val sessions: SlackCodexSessionStore,
    private val contextProvider: HouseholdContextSource,
    private val codex: CodexConversationClient,
    private val slack: SlackMessageClient,
    private val clock: Clock = Clock.systemUTC(),
    private val executor: Executor = Executors.newCachedThreadPool(),
    private val promptBuilder: SlackConversationPromptBuilder =
        SlackConversationPromptBuilderFactory.create(),
) : SlackConversationHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val queues = ConcurrentHashMap<SlackActorKey, SerialTaskQueue>()

    override fun submit(message: SlackConversationMessage) {
        val actor = SlackActorKey(message.teamId, message.slackUserId)
        queues.computeIfAbsent(actor) { SerialTaskQueueFactory.create(executor) }
            .execute { handle(message) }
    }

    override fun handle(message: SlackConversationMessage) {
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
            postRetryableError(message.channelId)
            return
        }
        if (!context.hasMatches) {
            sessions.markAnswerReady(key, NO_MATCH_ANSWER, now())
            deliverStoredAnswer(key, message.channelId, NO_MATCH_ANSWER)
            return
        }
        val prompt = promptBuilder.build(context.reference, message.text)
        val startedSession = AtomicReference<SlackCodexSession>()
        val result = if (active == null) {
            codex.start(prompt) { threadId ->
                val session = sessions.createAndActivate(principal, threadId, now())
                sessions.attachSession(key, session.id, now())
                startedSession.set(session)
            }
        } else {
            codex.resume(active.codexThreadId, prompt)
        }

        when (result) {
            is CodexTurnResult.Failure -> {
                sessions.markFailed(key, now())
                sessions.clearActive(principal)
                postRetryableError(message.channelId)
            }
            is CodexTurnResult.Success -> {
                val session = active ?: startedSession.get()
                if (session == null) {
                    sessions.markFailed(key, now())
                    sessions.clearActive(principal)
                    postRetryableError(message.channelId)
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
        try {
            val delivery = slack.postMessage(
                channelId = channelId,
                text = answer,
                blocks = emptyList(),
            )
            sessions.markCompleted(key, delivery.responseTs, now())
        } catch (error: Exception) {
            log.warn("Slack answer delivery failed category={}", error.deliveryCategory())
        }
    }

    private fun postRetryableError(channelId: String) {
        runCatching {
            slack.postMessage(
                channelId = channelId,
                text = "답변 처리에 실패했습니다. 새 메시지로 다시 시도해주세요.",
                blocks = emptyList(),
            )
        }
    }

    private fun now(): Long = clock.millis()

    private fun Exception.deliveryCategory(): String =
        (this as? SlackMessageDeliveryException)?.category ?: javaClass.simpleName

    companion object {
        const val SESSION_IDLE_TIMEOUT_MILLIS = 600_000L
        const val NO_MATCH_ANSWER = "승인된 기억에서 관련 내용을 찾지 못했습니다."
    }
}

private data class SlackActorKey(
    val teamId: String,
    val slackUserId: String,
)

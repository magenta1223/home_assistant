package com.homeassistant.application.usecase.chatservice

import com.homeassistant.application.port.inbound.chatservice.Chat
import com.homeassistant.application.port.inbound.chatservice.ChatRequest
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.inbound.chatservice.ChatResult
import com.homeassistant.application.port.outbound.chatgateway.ChatGateway
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatGatewayResult
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptChange
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptConflict
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatRequestStatus
import com.homeassistant.application.port.outbound.chatgateway.ChatSession
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionConflict
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionDeactivation
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRenewal
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStart
import com.homeassistant.application.usecase.contextprovider.ContextSource
import com.homeassistant.domain.identity.UserId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Answers a question from the user's readable episodes in a provider conversation. The conversation is resumed
 * while the user was active within [SESSION_IDLE_TIMEOUT_MILLIS] and started anew otherwise; a question with no
 * matching episode gets [NO_MATCH_ANSWER] without calling the provider.
 */
class ChatService(
    private val receipts: ChatReceiptRepository,
    private val sessions: ChatSessionRepository,
    private val contextSource: ContextSource,
    private val chatGateway: ChatGateway,
    private val clock: Clock = Clock.systemUTC(),
) : Chat {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun answer(request: ChatRequest): ChatResult {
        // TODO: ORCHESTRATION
        runCatching { receipts.save(ChatReceiptChange.Claim(request.key, now())) }.fold(
            onSuccess = {},
            onFailure = { error ->
                if (error !is ChatReceiptConflict) throw error
                return existingResult(request.key)
            },
        )

        val active = resumeActiveSession(request.userId)
        val contextStartedAt = System.nanoTime()
        val context = try {
            contextSource.context(request.userId, request.question)
        } catch (error: Exception) {
            log.warn(
                "Episode context retrieval failed category={} elapsedMs={}",
                error.javaClass.simpleName,
                elapsedMillis(contextStartedAt),
            )
            receipts.save(ChatReceiptChange.Fail(request.key, now()))
            return ChatResult.Failed
        }
        log.info(
            "Latency stage=episode-conversation-context elapsedMs={} hasMatches={} sessionMode={}",
            elapsedMillis(contextStartedAt),
            context.hasMatches,
            if (active == null) "start" else "resume",
        )
        if (!context.hasMatches) return answerReady(request.key, NO_MATCH_ANSWER)

        val prompt = ChatPromptBuilder.build(context.reference, request.question)
        val session = active ?: createSession(request) ?: return ChatResult.Failed
        val turnStartedAt = System.nanoTime()
        val result = chatGateway.chat(session.chatId, prompt)
        log.info(
            "Latency stage=episode-conversation-turn elapsedMs={} sessionMode={} result={}",
            elapsedMillis(turnStartedAt),
            if (active == null) "start" else "resume",
            if (result is ChatGatewayResult.Success) "success" else "failure",
        )

        return when (result) {
            ChatGatewayResult.Failure -> {
                release(session)
                receipts.save(ChatReceiptChange.Fail(request.key, now()))
                ChatResult.Failed
            }
            is ChatGatewayResult.Success -> {
                renew(session)
                answerReady(request.key, result.answer)
            }
        }
    }

    private fun existingResult(key: ChatRequestKey): ChatResult {
        val receipt = receipts.findByKey(key) ?: return ChatResult.AlreadyHandled
        return if (receipt.status == ChatRequestStatus.ANSWER_READY) {
            receipt.answerText
                ?.takeIf(String::isNotBlank)
                ?.let(ChatResult::AnswerReady)
                ?: ChatResult.AlreadyHandled
        } else {
            ChatResult.AlreadyHandled
        }
    }

    private fun answerReady(
        key: ChatRequestKey,
        answer: String,
    ): ChatResult.AnswerReady {
        receipts.save(ChatReceiptChange.AnswerReady(key, answer, now()))
        return ChatResult.AnswerReady(answer)
    }

    /** The user's active session renewed now, or null when there is none; an idle one is released instead. */
    private fun resumeActiveSession(userId: UserId): ChatSession? {
        val active = sessions.findActiveByUser(userId) ?: return null
        if (active.isIdleAt(now())) {
            release(active)
            return null
        }
        return renew(active)
    }

    /** [session] renewed now, or null when it stopped being active meanwhile. */
    private fun renew(session: ChatSession): ChatSession? =
        runCatching { sessions.save(ChatSessionRenewal(session.userId, session.id, now())) }.fold(
            onSuccess = { it },
            onFailure = { error ->
                if (error !is ChatSessionConflict) throw error
                null
            },
        )

    /** Deactivates [session] and ends its provider chat, unless the idle expiry already did both. */
    private fun release(session: ChatSession) {
        sessions.save(ChatSessionDeactivation.OfSession(session.userId, session.id))
            .forEach { chatGateway.end(it.chatId) }
    }

    /** A new provider chat made the user's active session and attached to the request, or null when that failed. */
    private fun createSession(request: ChatRequest): ChatSession? =
        chatGateway.begin().fold(
            onSuccess = { chatId -> activate(request, chatId) },
            onFailure = { error -> failSessionStart(request.key, error) },
        )

    private fun activate(request: ChatRequest, chatId: ChatId): ChatSession? =
        runCatching { sessions.save(ChatSessionStart(request.userId, chatId, now())) }.fold(
            onSuccess = { session -> attach(request.key, session) },
            onFailure = { error ->
                chatGateway.end(chatId)
                failSessionStart(request.key, error)
            },
        )

    private fun attach(key: ChatRequestKey, session: ChatSession): ChatSession? =
        runCatching { receipts.save(ChatReceiptChange.AttachSession(key, session.id, now())) }.fold(
            onSuccess = { session },
            onFailure = { error ->
                release(session)
                failSessionStart(key, error)
            },
        )

    private fun failSessionStart(key: ChatRequestKey, error: Throwable): ChatSession? {
        log.warn("Episode conversation session start failed category={}", error.javaClass.simpleName)
        receipts.save(ChatReceiptChange.Fail(key, now()))
        return null
    }

    private fun ChatSession.isIdleAt(now: Long): Boolean = now - lastActiveAt >= SESSION_IDLE_TIMEOUT_MILLIS

    private fun now(): Long = clock.millis()

    private fun elapsedMillis(startedAt: Long): Long =
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

    companion object {
        const val SESSION_IDLE_TIMEOUT_MILLIS = 600_000L
        const val NO_MATCH_ANSWER = "저장된 기억에서 관련 내용을 찾지 못했습니다."
    }
}

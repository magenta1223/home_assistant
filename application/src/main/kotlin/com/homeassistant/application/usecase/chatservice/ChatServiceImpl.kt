package com.homeassistant.application.usecase.chatservice

import com.homeassistant.application.port.inbound.chatservice.ChatService
import com.homeassistant.application.port.inbound.chatservice.ChatRequest
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.inbound.chatservice.ChatResult
import com.homeassistant.application.port.outbound.chatgateway.ChatGateway
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatGatewayResult
import com.homeassistant.application.port.outbound.chatgateway.ChatRequestStatus
import com.homeassistant.application.port.outbound.chatgateway.ChatSession
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionLease
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStore
import com.homeassistant.application.usecase.contextprovider.ContextSource
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Answers a question from the user's readable episodes in a provider conversation. The conversation is resumed
 * while the user was active within [SESSION_IDLE_TIMEOUT_MILLIS] and started anew otherwise; a question with no
 * matching episode gets [NO_MATCH_ANSWER] without calling the provider.
 */
class ChatServiceImpl(
    private val sessions: ChatSessionStore,
    private val contextProvider: ContextSource,
    private val chatGateway: ChatGateway,
    private val clock: Clock = Clock.systemUTC(),
) : ChatService {
    private val promptBuilder = ChatPromptBuilder
    private val log = LoggerFactory.getLogger(javaClass)

    override fun answer(request: ChatRequest): ChatResult {
        // TODO: ORCHESTRATION
        sessions.claimRequest(request.key, now()) ?: return existingResult(request.key)

        val active = when (val lease = sessions.lease(request.userId, now(), SESSION_IDLE_TIMEOUT_MILLIS)) {
            is ChatSessionLease.Active -> lease.session
            is ChatSessionLease.Expired -> {
                chatGateway.end(lease.session.chatId)
                null
            }
            ChatSessionLease.None -> null
        }
        val contextStartedAt = System.nanoTime()
        val context = try {
            contextProvider.context(request.userId, request.question)
        } catch (error: Exception) {
            log.warn(
                "Episode context retrieval failed category={} elapsedMs={}",
                error.javaClass.simpleName,
                elapsedMillis(contextStartedAt),
            )
            sessions.markFailed(request.key, now())
            return ChatResult.Failed
        }
        log.info(
            "Latency stage=episode-conversation-context elapsedMs={} hasMatches={} sessionMode={}",
            elapsedMillis(contextStartedAt),
            context.hasMatches,
            if (active == null) "start" else "resume",
        )
        if (!context.hasMatches) return answerReady(request.key, NO_MATCH_ANSWER)

        val prompt = promptBuilder.build(context.reference, request.question)
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
                chatGateway.end(session.chatId)
                sessions.markFailed(request.key, now())
                sessions.clearActive(request.userId)
                ChatResult.Failed
            }
            is ChatGatewayResult.Success -> {
                sessions.touch(request.userId, session.id, now())
                answerReady(request.key, result.answer)
            }
        }
    }

    private fun existingResult(key: ChatRequestKey): ChatResult {
        val receipt = sessions.receipt(key) ?: return ChatResult.AlreadyHandled
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
        sessions.markAnswerReady(key, answer, now())
        return ChatResult.AnswerReady(answer)
    }

    private fun createSession(request: ChatRequest): ChatSession? {
        var chatId: ChatId? = null
        return try {
            chatId = chatGateway.begin().getOrThrow()
            sessions.createAndActivate(request.userId, chatId, now()).also { session ->
                sessions.attachSession(request.key, session.id, now())
            }
        } catch (error: Exception) {
            log.warn("Episode conversation session start failed category={}", error.javaClass.simpleName)
            chatId?.let {
                chatGateway.end(it)
                sessions.clearActive(request.userId)
            }
            sessions.markFailed(request.key, now())
            null
        }
    }

    private fun now(): Long = clock.millis()

    private fun elapsedMillis(startedAt: Long): Long =
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

    companion object {
        const val SESSION_IDLE_TIMEOUT_MILLIS = 600_000L
        const val NO_MATCH_ANSWER = "저장된 기억에서 관련 내용을 찾지 못했습니다."
    }
}

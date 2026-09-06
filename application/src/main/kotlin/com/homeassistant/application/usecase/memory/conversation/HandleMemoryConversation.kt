package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import com.homeassistant.application.port.output.memory.conversation.ConversationThreadLifecycle
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnExecutor
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnResult
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationRequestStatus
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSession
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionLease
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

class HandleMemoryConversation(
    private val sessions: MemoryConversationSessionStore,
    private val contextProvider: MemoryConversationContextSource,
    private val threadLifecycle: ConversationThreadLifecycle,
    private val turnExecutor: ConversationTurnExecutor,
    private val clock: Clock = Clock.systemUTC(),
    private val promptBuilder: MemoryConversationPromptBuilder = MemoryConversationPromptBuilder(),
) : MemoryConversation {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun answer(request: MemoryConversationRequest): MemoryConversationResult {
        val claimed = sessions.claimRequest(request.key, now())
        if (claimed == null) return existingResult(request.key)

        val active = when (val lease = sessions.lease(request.participant, now(), SESSION_IDLE_TIMEOUT_MILLIS)) {
            is MemoryConversationSessionLease.Active -> lease.session
            is MemoryConversationSessionLease.Expired -> {
                threadLifecycle.end(lease.session.conversationThreadId)
                null
            }
            MemoryConversationSessionLease.None -> null
        }
        val contextStartedAt = System.nanoTime()
        val context = try {
            contextProvider.context(request.participant.userId, request.question)
        } catch (error: Exception) {
            log.warn(
                "Memory context retrieval failed category={} elapsedMs={}",
                error.javaClass.simpleName,
                elapsedMillis(contextStartedAt),
            )
            sessions.markFailed(request.key, now())
            return MemoryConversationResult.Failed
        }
        log.info(
            "Latency stage=memory-conversation-context elapsedMs={} hasMatches={} sessionMode={}",
            elapsedMillis(contextStartedAt),
            context.hasMatches,
            if (active == null) "start" else "resume",
        )
        if (!context.hasMatches) return answerReady(request.key, NO_MATCH_ANSWER)

        val prompt = promptBuilder.build(context.reference, request.question)
        val session = active ?: createSession(request) ?: return MemoryConversationResult.Failed
        val turnStartedAt = System.nanoTime()
        val result = turnExecutor.execute(session.conversationThreadId, prompt)
        log.info(
            "Latency stage=memory-conversation-turn elapsedMs={} sessionMode={} result={}",
            elapsedMillis(turnStartedAt),
            if (active == null) "start" else "resume",
            if (result is ConversationTurnResult.Success) "success" else "failure",
        )

        return when (result) {
            ConversationTurnResult.Failure -> {
                threadLifecycle.end(session.conversationThreadId)
                sessions.markFailed(request.key, now())
                sessions.clearActive(request.participant)
                MemoryConversationResult.Failed
            }
            is ConversationTurnResult.Success -> {
                sessions.touch(request.participant, session.id, now())
                answerReady(request.key, result.answer)
            }
        }
    }

    override fun markDelivered(key: MemoryConversationRequestKey, deliveryId: String) {
        sessions.markCompleted(key, deliveryId, now())
    }

    private fun existingResult(key: MemoryConversationRequestKey): MemoryConversationResult {
        val receipt = sessions.receipt(key) ?: return MemoryConversationResult.AlreadyHandled
        return if (receipt.status == MemoryConversationRequestStatus.ANSWER_READY) {
            receipt.answerText
                ?.takeIf(String::isNotBlank)
                ?.let(MemoryConversationResult::AnswerReady)
                ?: MemoryConversationResult.AlreadyHandled
        } else {
            MemoryConversationResult.AlreadyHandled
        }
    }

    private fun answerReady(
        key: MemoryConversationRequestKey,
        answer: String,
    ): MemoryConversationResult.AnswerReady {
        sessions.markAnswerReady(key, answer, now())
        return MemoryConversationResult.AnswerReady(answer)
    }

    private fun createSession(request: MemoryConversationRequest): MemoryConversationSession? {
        var threadId: String? = null
        return try {
            threadId = threadLifecycle.create()
            sessions.createAndActivate(request.participant, threadId, now()).also { session ->
                sessions.attachSession(request.key, session.id, now())
            }
        } catch (error: Exception) {
            log.warn("Memory conversation session start failed category={}", error.javaClass.simpleName)
            threadId?.let {
                threadLifecycle.end(it)
                sessions.clearActive(request.participant)
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

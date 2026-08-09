package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnClient
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnResult
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationRequestStatus
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSession
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

class HandleMemoryConversation(
    private val sessions: MemoryConversationSessionStore,
    private val contextProvider: MemoryConversationContextSource,
    private val conversationClient: ConversationTurnClient,
    private val clock: Clock = Clock.systemUTC(),
    private val promptBuilder: MemoryConversationPromptBuilder = MemoryConversationPromptBuilder(),
) : MemoryConversation {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun answer(request: MemoryConversationRequest): MemoryConversationResult {
        val claimed = sessions.claimRequest(request.key, now())
        if (claimed == null) return existingResult(request.key)

        val active = sessions.active(request.participant, now(), SESSION_IDLE_TIMEOUT_MILLIS)
        val context = try {
            contextProvider.context(request.participant.userId, request.question)
        } catch (error: Exception) {
            log.warn("Memory context retrieval failed category={}", error.javaClass.simpleName)
            sessions.markFailed(request.key, now())
            return MemoryConversationResult.Failed
        }
        if (!context.hasMatches) return answerReady(request.key, NO_MATCH_ANSWER)

        val prompt = promptBuilder.build(context.reference, request.question)
        val startedSession = AtomicReference<MemoryConversationSession>()
        val result = if (active == null) {
            conversationClient.start(prompt) { threadId ->
                val session = sessions.createAndActivate(request.participant, threadId, now())
                sessions.attachSession(request.key, session.id, now())
                startedSession.set(session)
            }
        } else {
            conversationClient.resume(active.conversationThreadId, prompt)
        }

        return when (result) {
            is ConversationTurnResult.Failure -> {
                sessions.markFailed(request.key, now())
                sessions.clearActive(request.participant)
                MemoryConversationResult.Failed
            }
            is ConversationTurnResult.Success -> {
                val session = active ?: startedSession.get()
                if (session == null) {
                    sessions.markFailed(request.key, now())
                    sessions.clearActive(request.participant)
                    MemoryConversationResult.Failed
                } else {
                    sessions.touch(request.participant, session.id, now())
                    answerReady(request.key, result.answer)
                }
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

    private fun now(): Long = clock.millis()

    companion object {
        const val SESSION_IDLE_TIMEOUT_MILLIS = 600_000L
        const val NO_MATCH_ANSWER = "저장된 기억에서 관련 내용을 찾지 못했습니다."
    }
}

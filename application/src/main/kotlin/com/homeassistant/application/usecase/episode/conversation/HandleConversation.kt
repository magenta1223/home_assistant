package com.homeassistant.application.usecase.episode.conversation

import com.homeassistant.application.port.inbound.episode.Conversation
import com.homeassistant.application.port.inbound.episode.ConversationRequest
import com.homeassistant.application.port.inbound.episode.ConversationRequestKey
import com.homeassistant.application.port.inbound.episode.ConversationResult
import com.homeassistant.application.port.outbound.episode.conversation.ConversationGateway
import com.homeassistant.application.port.outbound.episode.conversation.ConversationId
import com.homeassistant.application.port.outbound.episode.conversation.ConversationReply
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationRequestStatus
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSession
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionLease
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Answers a question from the participant's readable episodes in a provider conversation. The conversation is resumed
 * while the participant was active within [SESSION_IDLE_TIMEOUT_MILLIS] and started anew otherwise; a question with no
 * matching episode gets [NO_MATCH_ANSWER] without calling the provider.
 */
class HandleConversation(
    private val sessions: EpisodeConversationSessionStore,
    private val contextProvider: EpisodeConversationContextSource,
    private val conversationGateway: ConversationGateway,
    private val clock: Clock = Clock.systemUTC(),
    private val promptBuilder: EpisodeConversationPromptBuilder = EpisodeConversationPromptBuilder(),
) : Conversation {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun answer(request: ConversationRequest): ConversationResult {
        sessions.claimRequest(request.key, now()) ?: return existingResult(request.key)

        val active = when (val lease = sessions.lease(request.participant, now(), SESSION_IDLE_TIMEOUT_MILLIS)) {
            is EpisodeConversationSessionLease.Active -> lease.session
            is EpisodeConversationSessionLease.Expired -> {
                conversationGateway.end(lease.session.conversationId)
                null
            }
            EpisodeConversationSessionLease.None -> null
        }
        val contextStartedAt = System.nanoTime()
        val context = try {
            contextProvider.context(request.participant.userId, request.question)
        } catch (error: Exception) {
            log.warn(
                "Episode context retrieval failed category={} elapsedMs={}",
                error.javaClass.simpleName,
                elapsedMillis(contextStartedAt),
            )
            sessions.markFailed(request.key, now())
            return ConversationResult.Failed
        }
        log.info(
            "Latency stage=episode-conversation-context elapsedMs={} hasMatches={} sessionMode={}",
            elapsedMillis(contextStartedAt),
            context.hasMatches,
            if (active == null) "start" else "resume",
        )
        if (!context.hasMatches) return answerReady(request.key, NO_MATCH_ANSWER)

        val prompt = promptBuilder.build(context.reference, request.question)
        val session = active ?: createSession(request) ?: return ConversationResult.Failed
        val turnStartedAt = System.nanoTime()
        val result = conversationGateway.continueConversation(session.conversationId, prompt)
        log.info(
            "Latency stage=episode-conversation-turn elapsedMs={} sessionMode={} result={}",
            elapsedMillis(turnStartedAt),
            if (active == null) "start" else "resume",
            if (result is ConversationReply.Success) "success" else "failure",
        )

        return when (result) {
            ConversationReply.Failure -> {
                conversationGateway.end(session.conversationId)
                sessions.markFailed(request.key, now())
                sessions.clearActive(request.participant)
                ConversationResult.Failed
            }
            is ConversationReply.Success -> {
                sessions.touch(request.participant, session.id, now())
                answerReady(request.key, result.answer)
            }
        }
    }

    private fun existingResult(key: ConversationRequestKey): ConversationResult {
        val receipt = sessions.receipt(key) ?: return ConversationResult.AlreadyHandled
        return if (receipt.status == EpisodeConversationRequestStatus.ANSWER_READY) {
            receipt.answerText
                ?.takeIf(String::isNotBlank)
                ?.let(ConversationResult::AnswerReady)
                ?: ConversationResult.AlreadyHandled
        } else {
            ConversationResult.AlreadyHandled
        }
    }

    private fun answerReady(
        key: ConversationRequestKey,
        answer: String,
    ): ConversationResult.AnswerReady {
        sessions.markAnswerReady(key, answer, now())
        return ConversationResult.AnswerReady(answer)
    }

    private fun createSession(request: ConversationRequest): EpisodeConversationSession? {
        var conversationId: ConversationId? = null
        return try {
            conversationId = conversationGateway.begin().getOrThrow()
            sessions.createAndActivate(request.participant, conversationId, now()).also { session ->
                sessions.attachSession(request.key, session.id, now())
            }
        } catch (error: Exception) {
            log.warn("Episode conversation session start failed category={}", error.javaClass.simpleName)
            conversationId?.let {
                conversationGateway.end(it)
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

package com.homeassistant.application.usecase.episode.conversation

import com.homeassistant.application.port.inbound.episode.EpisodeConversationParticipant
import com.homeassistant.application.port.inbound.episode.EpisodeConversationRequestKey
import com.homeassistant.application.port.outbound.episode.conversation.ConversationGateway
import com.homeassistant.application.port.outbound.episode.conversation.ConversationId
import com.homeassistant.application.port.outbound.episode.conversation.ConversationReply
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationReceipt
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSession
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionLease
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionStore
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpireIdleEpisodeConversationsTest {
    @Test
    fun `releases every session idle for ten minutes`() {
        val store = ExpiringStore(
            listOf(
                session(1, "thread-a", NOW - TIMEOUT),
                session(2, "thread-b", NOW - TIMEOUT - 1),
                session(3, "thread-c", NOW - TIMEOUT + 1),
            ),
        )
        val client = EndingClient()
        val useCase = ExpireIdleEpisodeConversations(
            sessions = store,
            conversationGateway = client,
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        )

        assertEquals(2, useCase.execute())
        assertEquals(listOf("thread-a", "thread-b"), client.ended)
        assertEquals(listOf("thread-c"), store.remaining.map { it.conversationId.value })
    }

    private class EndingClient : ConversationGateway {
        val ended = mutableListOf<String>()
        override fun begin(): Result<ConversationId> = error("unused")
        override fun continueConversation(conversationId: ConversationId, prompt: String): ConversationReply = error("unused")
        override fun end(conversationId: ConversationId) {
            ended += conversationId.value
        }
        override fun close() = Unit
    }

    private class ExpiringStore(sessions: List<EpisodeConversationSession>) : EpisodeConversationSessionStore {
        val remaining = sessions.toMutableList()

        override fun expireIdle(beforeInclusive: Long): List<EpisodeConversationSession> {
            val expired = remaining.filter { it.lastActiveAt <= beforeInclusive }
            remaining.removeAll(expired)
            return expired
        }

        override fun claimRequest(key: EpisodeConversationRequestKey, now: Long): EpisodeConversationReceipt? = error("unused")
        override fun receipt(key: EpisodeConversationRequestKey): EpisodeConversationReceipt? = error("unused")
        override fun attachSession(key: EpisodeConversationRequestKey, sessionId: Int, now: Long) = error("unused")
        override fun markAnswerReady(key: EpisodeConversationRequestKey, answer: String, now: Long) = error("unused")
        override fun markFailed(key: EpisodeConversationRequestKey, now: Long) = error("unused")
        override fun createAndActivate(
            participant: EpisodeConversationParticipant,
            conversationId: ConversationId,
            now: Long,
        ): EpisodeConversationSession = error("unused")
        override fun lease(
            participant: EpisodeConversationParticipant,
            now: Long,
            idleTimeoutMillis: Long,
        ): EpisodeConversationSessionLease = error("unused")
        override fun clearActive(participant: EpisodeConversationParticipant) = error("unused")
        override fun touch(participant: EpisodeConversationParticipant, sessionId: Int, now: Long) = error("unused")
        override fun failStaleProcessing(before: Long, now: Long): Int = error("unused")
    }

    private companion object {
        const val NOW = 1_000_000L
        const val TIMEOUT = HandleEpisodeConversation.SESSION_IDLE_TIMEOUT_MILLIS

        fun session(id: Int, threadId: String, lastActiveAt: Long) = EpisodeConversationSession(
            id = id,
            participant = EpisodeConversationParticipant("scope", "participant-$id", UserId("user-$id")),
            conversationId = ConversationId(threadId),
            createdAt = 0,
            lastActiveAt = lastActiveAt,
        )
    }
}

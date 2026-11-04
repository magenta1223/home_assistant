package com.homeassistant.application.usecase.episode.chat

import com.homeassistant.application.port.inbound.chatservice.ChatParticipant
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.outbound.episode.conversation.ChatGateway
import com.homeassistant.application.port.outbound.episode.conversation.ChatId
import com.homeassistant.application.port.outbound.episode.conversation.ChatGatewayResult
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationReceipt
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSession
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionLease
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionStore
import com.homeassistant.application.usecase.chatservice.ChatServiceImpl
import com.homeassistant.application.usecase.chatservice.ExpireIdleChats
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpireIdleConversationsTest {
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
        val useCase = ExpireIdleChats(
            sessions = store,
            chatGateway = client,
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        )

        assertEquals(2, useCase.execute())
        assertEquals(listOf("thread-a", "thread-b"), client.ended)
        assertEquals(listOf("thread-c"), store.remaining.map { it.chatId.value })
    }

    private class EndingClient : ChatGateway {
        val ended = mutableListOf<String>()
        override fun begin(): Result<ChatId> = error("unused")
        override fun chat(chatId: ChatId, prompt: String): ChatGatewayResult = error("unused")
        override fun end(chatId: ChatId) {
            ended += chatId.value
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

        override fun claimRequest(key: ChatRequestKey, now: Long): EpisodeConversationReceipt? = error("unused")
        override fun receipt(key: ChatRequestKey): EpisodeConversationReceipt? = error("unused")
        override fun attachSession(key: ChatRequestKey, sessionId: Int, now: Long) = error("unused")
        override fun markAnswerReady(key: ChatRequestKey, answer: String, now: Long) = error("unused")
        override fun markFailed(key: ChatRequestKey, now: Long) = error("unused")
        override fun createAndActivate(
            participant: ChatParticipant,
            chatId: ChatId,
            now: Long,
        ): EpisodeConversationSession = error("unused")
        override fun lease(
            participant: ChatParticipant,
            now: Long,
            idleTimeoutMillis: Long,
        ): EpisodeConversationSessionLease = error("unused")
        override fun clearActive(participant: ChatParticipant) = error("unused")
        override fun touch(participant: ChatParticipant, sessionId: Int, now: Long) = error("unused")
        override fun failStaleProcessing(before: Long, now: Long): Int = error("unused")
    }

    private companion object {
        const val NOW = 1_000_000L
        const val TIMEOUT = ChatServiceImpl.SESSION_IDLE_TIMEOUT_MILLIS

        fun session(id: Int, threadId: String, lastActiveAt: Long) = EpisodeConversationSession(
            id = id,
            participant = ChatParticipant("scope", "participant-$id", UserId("user-$id")),
            chatId = ChatId(threadId),
            createdAt = 0,
            lastActiveAt = lastActiveAt,
        )
    }
}

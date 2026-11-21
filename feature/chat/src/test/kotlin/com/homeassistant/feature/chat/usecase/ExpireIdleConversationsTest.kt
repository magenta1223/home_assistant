package com.homeassistant.feature.chat.usecase

import com.homeassistant.feature.chat.port.outbound.ChatGateway
import com.homeassistant.feature.chat.port.outbound.ChatId
import com.homeassistant.feature.chat.port.outbound.ChatGatewayResult
import com.homeassistant.feature.chat.port.outbound.ChatSession
import com.homeassistant.feature.chat.port.outbound.ChatSessionDeactivation
import com.homeassistant.feature.chat.port.outbound.ChatSessionRenewal
import com.homeassistant.feature.chat.port.outbound.ChatSessionRepository
import com.homeassistant.feature.chat.port.outbound.ChatSessionStart
import com.homeassistant.feature.chat.port.outbound.ChatTurn
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
        val useCase = ExpireIdleChatsService(
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
        override fun chat(chatId: ChatId, turn: ChatTurn): ChatGatewayResult = error("unused")
        override fun end(chatId: ChatId) {
            ended += chatId.value
        }
    }

    private class ExpiringStore(sessions: List<ChatSession>) : ChatSessionRepository {
        val remaining = sessions.toMutableList()

        override fun save(deactivation: ChatSessionDeactivation): List<ChatSession> {
            val idleSince = assertIs<ChatSessionDeactivation.IdleSince>(deactivation)
            val expired = remaining.filter { it.lastActiveAt <= idleSince.beforeInclusive }
            remaining.removeAll(expired)
            return expired
        }

        override fun findActiveByUser(userId: UserId): ChatSession? = error("unused")
        override fun save(start: ChatSessionStart): ChatSession = error("unused")
        override fun save(renewal: ChatSessionRenewal): ChatSession = error("unused")
    }

    private companion object {
        const val NOW = 1_000_000L
        const val TIMEOUT = ChatService.SESSION_IDLE_TIMEOUT_MILLIS

        fun session(id: Int, threadId: String, lastActiveAt: Long) = ChatSession(
            id = id,
            userId = UserId("user-$id"),
            chatId = ChatId(threadId),
            createdAt = 0,
            lastActiveAt = lastActiveAt,
        )
    }
}

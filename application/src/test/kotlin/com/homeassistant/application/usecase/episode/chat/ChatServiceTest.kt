package com.homeassistant.application.usecase.episode.chat

import com.homeassistant.application.port.inbound.chatservice.ChatRequest
import com.homeassistant.application.port.inbound.chatservice.ChatResult
import com.homeassistant.application.port.outbound.chatgateway.ChatGateway
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatGatewayResult
import com.homeassistant.application.port.outbound.chatgateway.ChatSession
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionConflict
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionDeactivation
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRenewal
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStart
import com.homeassistant.application.port.outbound.chatgateway.ChatTurn
import com.homeassistant.application.usecase.contextprovider.ChatContext
import com.homeassistant.application.usecase.contextprovider.ContextSource
import com.homeassistant.application.usecase.chatservice.ChatContextDelivery
import com.homeassistant.application.usecase.chatservice.ChatService
import com.homeassistant.application.usecase.contexttool.ContextGrants
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatServiceTest {
    @Test
    fun `answers from saved episodes in a new provider chat`() {
        val events = mutableListOf<String>()
        val store = RecordingSessionStore(events = events)
        val client = RecordingChatGateway(events = events)
        val seenUsers = mutableListOf<UserId>()
        val handler = handler(store, client) { userId, _ ->
            seenUsers += userId
            ChatContext("saved episode", hasMatches = true)
        }

        val result = handler.answer(REQUEST)

        assertEquals(ChatResult.AnswerReady("grounded answer"), result)
        assertEquals(listOf(USER_ID), seenUsers)
        assertTrue(client.executedPrompt.orEmpty().contains("saved episode"))
        assertEquals(listOf("begin", "start", "continue"), events)
    }

    @Test
    fun `returns no-match answer without starting a model conversation`() {
        val store = RecordingSessionStore()
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ ->
            ChatContext("", hasMatches = false)
        }

        val result = handler.answer(REQUEST)

        assertEquals(
            ChatResult.AnswerReady(ChatService.NO_MATCH_ANSWER),
            result,
        )
        assertEquals(null, client.executedPrompt)
    }

    @Test
    fun `executes on the user active session within its idle lease`() {
        val store = RecordingSessionStore().apply {
            save(ChatSessionStart(USER_ID, ChatId("existing-thread"), NOW - 1))
        }
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertIs<ChatResult.AnswerReady>(handler.answer(REQUEST))

        assertEquals("existing-thread", client.executedThreadId)
        assertEquals(emptyList(), client.createdThreadIds)
    }

    @Test
    fun `starts a new thread when the active session expires before it is renewed`() {
        val store = RecordingSessionStore(expireOnRenewal = true).apply {
            save(ChatSessionStart(USER_ID, ChatId("existing-thread"), NOW - 1))
        }
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertIs<ChatResult.AnswerReady>(handler.answer(REQUEST))

        assertEquals(listOf("new-thread-1"), client.createdThreadIds)
        assertEquals("new-thread-1", client.executedThreadId)
    }

    @Test
    fun `expires an idle thread before starting a replacement`() {
        val store = RecordingSessionStore().apply {
            save(ChatSessionStart(USER_ID, ChatId("expired-thread"), NOW - ChatService.SESSION_IDLE_TIMEOUT_MILLIS))
        }
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertIs<ChatResult.AnswerReady>(handler.answer(REQUEST))

        assertEquals(listOf("expired-thread"), client.endedThreadIds)
        assertEquals(listOf("new-thread-1"), client.createdThreadIds)
        assertEquals("new-thread-1", client.executedThreadId)
    }

    @Test
    fun `keeps different users on different conversation threads`() {
        val store = RecordingSessionStore()
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }
        val secondUser = UserId("member-2")

        handler.answer(REQUEST)
        handler.answer(
            ChatRequest(
                secondUser,
                "question",
            ),
        )
        handler.answer(
            ChatRequest(
                USER_ID,
                "follow up",
            ),
        )
        handler.answer(
            ChatRequest(
                secondUser,
                "follow up",
            ),
        )

        assertEquals(
            listOf("new-thread-1", "new-thread-2", "new-thread-1", "new-thread-2"),
            client.executedThreadIds,
        )
    }

    @Test
    fun `ends a newly created thread when its turn fails`() {
        val store = RecordingSessionStore()
        val client = RecordingChatGateway(turnResult = ChatGatewayResult.Failure)
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertEquals(ChatResult.Failed, handler.answer(REQUEST))

        assertEquals(listOf("new-thread-1"), client.endedThreadIds)
        assertNull(store.findActiveByUser(USER_ID))
    }

    @Test
    fun `ends an active thread when its turn fails`() {
        val store = RecordingSessionStore().apply {
            save(ChatSessionStart(USER_ID, ChatId("existing-thread"), NOW - 1))
        }
        val client = RecordingChatGateway(turnResult = ChatGatewayResult.Failure)
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertEquals(ChatResult.Failed, handler.answer(REQUEST))

        assertEquals(emptyList(), client.createdThreadIds)
        assertEquals(listOf("existing-thread"), client.endedThreadIds)
        assertNull(store.findActiveByUser(USER_ID))
    }

    @Test
    fun `does not execute a turn when thread creation fails`() {
        val store = RecordingSessionStore()
        val client = RecordingChatGateway(createFailure = IllegalStateException("create failed"))
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertEquals(ChatResult.Failed, handler.answer(REQUEST))

        assertEquals(null, client.executedThreadId)
    }

    @Test
    fun `ends a created thread when its session cannot be stored`() {
        val store = RecordingSessionStore(failStart = true)
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertEquals(ChatResult.Failed, handler.answer(REQUEST))

        assertEquals(listOf("new-thread-1"), client.endedThreadIds)
        assertEquals(null, client.executedThreadId)
        assertNull(store.findActiveByUser(USER_ID))
    }

    @Test
    fun `with context tools the provider gets a token for the user that ends with the turn`() {
        val grants = ContextGrants(Duration.ofMinutes(10), FIXED_CLOCK)
        val ownersDuringTurn = mutableListOf<UserId>()
        val client = RecordingChatGateway(
            onTurn = { turn -> ownersDuringTurn += grants.ownerOf(assertIs<ChatTurn.WithContextTools>(turn).contextToken)!! },
        )
        val handler = ChatService(RecordingSessionStore(), ChatContextDelivery.OnRequest(grants), client, FIXED_CLOCK)

        assertEquals(ChatResult.AnswerReady("grounded answer"), handler.answer(REQUEST))

        val turn = assertIs<ChatTurn.WithContextTools>(client.executedTurn)
        assertEquals(listOf(USER_ID), ownersDuringTurn)
        assertTrue(turn.prompt.contains("search_memory"))
        assertTrue(turn.prompt.contains("question"))
        assertNull(grants.ownerOf(turn.contextToken))
    }

    @Test
    fun `with context tools a failed turn still ends the token`() {
        val grants = ContextGrants(Duration.ofMinutes(10), FIXED_CLOCK)
        val client = RecordingChatGateway(turnResult = ChatGatewayResult.Failure)
        val handler = ChatService(RecordingSessionStore(), ChatContextDelivery.OnRequest(grants), client, FIXED_CLOCK)

        assertEquals(ChatResult.Failed, handler.answer(REQUEST))

        val token = assertIs<ChatTurn.WithContextTools>(client.executedTurn).contextToken
        assertNull(grants.ownerOf(token))
    }

    private fun handler(
        store: RecordingSessionStore,
        client: RecordingChatGateway,
        chatContext: (UserId, String) -> ChatContext,
    ) = ChatService(
        sessions = store,
        contextDelivery = ChatContextDelivery.Prefetch(ContextSource(chatContext)),
        chatGateway = client,
        clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
    )

    private class RecordingChatGateway(
        private val turnResult: ChatGatewayResult = ChatGatewayResult.Success("grounded answer"),
        private val createFailure: Exception? = null,
        private val events: MutableList<String>? = null,
        private val onTurn: (ChatTurn) -> Unit = {},
    ) : ChatGateway {
        var executedPrompt: String? = null
        var executedTurn: ChatTurn? = null
        var executedThreadId: String? = null
        val createdThreadIds = mutableListOf<String>()
        val executedThreadIds = mutableListOf<String>()
        val endedThreadIds = mutableListOf<String>()
        private var nextThreadId = 1

        override fun begin(): Result<ChatId> {
            createFailure?.let { return Result.failure(it) }
            events?.add("begin")
            return Result.success(
                ChatId("new-thread-${nextThreadId++}").also { createdThreadIds += it.value },
            )
        }

        override fun chat(chatId: ChatId, turn: ChatTurn): ChatGatewayResult {
            events?.add("continue")
            executedTurn = turn
            onTurn(turn)
            executedPrompt = turn.prompt
            executedThreadId = chatId.value
            executedThreadIds += chatId.value
            return turnResult
        }

        override fun end(chatId: ChatId) {
            endedThreadIds += chatId.value
        }
    }

    private class RecordingSessionStore(
        private val failStart: Boolean = false,
        private val expireOnRenewal: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : ChatSessionRepository {
        private val activeSessions = mutableMapOf<UserId, ChatSession>()
        private var nextSessionId = 1

        override fun findActiveByUser(userId: UserId): ChatSession? = activeSessions[userId]

        override fun save(start: ChatSessionStart): ChatSession = if (failStart) error("start failed") else ChatSession(
            id = nextSessionId++,
            userId = start.userId,
            chatId = start.chatId,
            createdAt = start.now,
            lastActiveAt = start.now,
        ).also {
            events?.add("start")
            activeSessions[start.userId] = it
        }

        override fun save(renewal: ChatSessionRenewal): ChatSession {
            if (expireOnRenewal) activeSessions.remove(renewal.userId)
            val active = activeSessions[renewal.userId]?.takeIf { it.id == renewal.sessionId }
                ?: throw ChatSessionConflict(renewal.sessionId)
            return active.copy(lastActiveAt = renewal.now).also { activeSessions[renewal.userId] = it }
        }

        override fun save(deactivation: ChatSessionDeactivation): List<ChatSession> {
            val deactivated = when (deactivation) {
                is ChatSessionDeactivation.OfSession ->
                    listOfNotNull(activeSessions[deactivation.userId]?.takeIf { it.id == deactivation.sessionId })
                is ChatSessionDeactivation.IdleSince ->
                    activeSessions.values.filter { it.lastActiveAt <= deactivation.beforeInclusive }
            }
            deactivated.forEach { activeSessions.remove(it.userId) }
            return deactivated
        }
    }

    private companion object {
        const val NOW = 1_000_000L
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
        val USER_ID = UserId("member-1")
        val REQUEST = ChatRequest(USER_ID, "question")
    }
}

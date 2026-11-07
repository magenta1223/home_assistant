package com.homeassistant.application.usecase.episode.chat

import com.homeassistant.application.port.inbound.chatservice.ChatRequest
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.inbound.chatservice.ChatResult
import com.homeassistant.application.port.outbound.chatgateway.ChatGateway
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatGatewayResult
import com.homeassistant.application.port.outbound.chatgateway.ChatReceipt
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
import com.homeassistant.application.port.outbound.chatgateway.StaleProcessingFailure
import com.homeassistant.application.usecase.contextprovider.ChatContext
import com.homeassistant.application.usecase.contextprovider.ContextSource
import com.homeassistant.application.usecase.chatservice.ChatService
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatServiceTest {
    @Test
    fun `builds and stores an answer for HTTP request retries`() {
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
        assertEquals(listOf("begin", "start", "attachSession", "continue"), events)
        assertEquals(ChatRequestStatus.ANSWER_READY, store.findByKey(REQUEST.key)?.status)

    }

    @Test
    fun `returns a stored answer without invoking the model again`() {
        val store = RecordingSessionStore().apply {
            save(ChatReceiptChange.Claim(REQUEST.key, NOW))
            save(ChatReceiptChange.AnswerReady(REQUEST.key, "stored answer", NOW))
        }
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ -> error("context must not be rebuilt") }

        val result = handler.answer(REQUEST)

        assertEquals(ChatResult.AnswerReady("stored answer"), result)
        assertEquals(null, client.executedPrompt)
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
                "request-1",
                "question",
            ),
        )
        handler.answer(
            ChatRequest(
                USER_ID,
                "request-2",
                "follow up",
            ),
        )
        handler.answer(
            ChatRequest(
                secondUser,
                "request-2",
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
        assertEquals(ChatRequestStatus.FAILED, store.findByKey(REQUEST.key)?.status)
    }

    @Test
    fun `ends a created thread when session attachment fails`() {
        val store = RecordingSessionStore(failAttachSession = true)
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertEquals(ChatResult.Failed, handler.answer(REQUEST))

        assertEquals(listOf("new-thread-1"), client.endedThreadIds)
        assertEquals(null, client.executedThreadId)
        assertNull(store.findActiveByUser(USER_ID))
    }

    private fun handler(
        store: RecordingSessionStore,
        client: RecordingChatGateway,
        chatContext: (UserId, String) -> ChatContext,
    ) = ChatService(
        receipts = store,
        sessions = store,
        contextSource = ContextSource(chatContext),
        chatGateway = client,
        clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
    )

    private class RecordingChatGateway(
        private val turnResult: ChatGatewayResult = ChatGatewayResult.Success("grounded answer"),
        private val createFailure: Exception? = null,
        private val events: MutableList<String>? = null,
    ) : ChatGateway {
        var executedPrompt: String? = null
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

        override fun chat(chatId: ChatId, prompt: String): ChatGatewayResult {
            events?.add("continue")
            executedPrompt = prompt
            executedThreadId = chatId.value
            executedThreadIds += chatId.value
            return turnResult
        }

        override fun end(chatId: ChatId) {
            endedThreadIds += chatId.value
        }
    }

    private class RecordingSessionStore(
        private val failAttachSession: Boolean = false,
        private val expireOnRenewal: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : ChatSessionRepository, ChatReceiptRepository {
        private val receipts = mutableMapOf<ChatRequestKey, ChatReceipt>()
        private val activeSessions = mutableMapOf<UserId, ChatSession>()
        private var nextSessionId = 1

        override fun findByKey(key: ChatRequestKey): ChatReceipt? = receipts[key]

        override fun save(change: ChatReceiptChange): ChatReceipt {
            val receipt = when (change) {
                is ChatReceiptChange.Claim -> {
                    if (change.key in receipts) throw ChatReceiptConflict(change.key)
                    ChatReceipt(change.key, ChatRequestStatus.PROCESSING, createdAt = change.now, updatedAt = change.now)
                }
                is ChatReceiptChange.AttachSession -> {
                    events?.add("attachSession")
                    if (failAttachSession) error("attach failed")
                    receipts.getValue(change.key).copy(sessionId = change.sessionId, updatedAt = change.now)
                }
                is ChatReceiptChange.AnswerReady -> receipts.getValue(change.key).copy(
                    status = ChatRequestStatus.ANSWER_READY,
                    answerText = change.answer,
                    updatedAt = change.now,
                )
                is ChatReceiptChange.Fail -> receipts.getValue(change.key).copy(
                    status = ChatRequestStatus.FAILED,
                    updatedAt = change.now,
                )
            }
            receipts[change.key] = receipt
            return receipt
        }

        override fun save(failure: StaleProcessingFailure): List<ChatReceipt> = error("unused")

        override fun findActiveByUser(userId: UserId): ChatSession? = activeSessions[userId]

        override fun save(start: ChatSessionStart): ChatSession = ChatSession(
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
        val USER_ID = UserId("member-1")
        val REQUEST = ChatRequest(USER_ID, "request-1", "question")
    }
}

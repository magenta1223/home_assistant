package com.homeassistant.application.usecase.episode.chat

import com.homeassistant.application.port.inbound.chatservice.ChatParticipant
import com.homeassistant.application.port.inbound.chatservice.ChatRequest
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.inbound.chatservice.ChatResult
import com.homeassistant.application.port.outbound.episode.conversation.ChatGateway
import com.homeassistant.application.port.outbound.episode.conversation.ChatId
import com.homeassistant.application.port.outbound.episode.conversation.ChatGatewayResult
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationReceipt
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationRequestStatus
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSession
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionLease
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionStore
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatServiceImplTest {
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
        assertEquals(listOf("begin", "createAndActivate", "attachSession", "continue"), events)
        assertEquals(EpisodeConversationRequestStatus.ANSWER_READY, store.receipt(KEY)?.status)

    }

    @Test
    fun `returns a stored answer without invoking the model again`() {
        val store = RecordingSessionStore().apply {
            claimRequest(KEY, NOW)
            markAnswerReady(KEY, "stored answer", NOW)
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
            ChatResult.AnswerReady(ChatServiceImpl.NO_MATCH_ANSWER),
            result,
        )
        assertEquals(null, client.executedPrompt)
    }

    @Test
    fun `executes on the participant active session within its idle lease`() {
        val store = RecordingSessionStore().apply {
            createAndActivate(PARTICIPANT, ChatId("existing-thread"), NOW - 1)
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
    fun `expires an idle thread before starting a replacement`() {
        val store = RecordingSessionStore().apply {
            createAndActivate(
                PARTICIPANT,
                ChatId("expired-thread"),
                NOW - ChatServiceImpl.SESSION_IDLE_TIMEOUT_MILLIS,
            )
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
    fun `keeps different participants on different conversation threads`() {
        val store = RecordingSessionStore()
        val client = RecordingChatGateway()
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }
        val secondParticipant = ChatParticipant(
            scopeId = "workspace-1",
            participantId = "member-2",
            userId = UserId("user-2"),
        )

        handler.answer(REQUEST)
        handler.answer(
            ChatRequest(
                secondParticipant,
                ChatRequestKey("stream-2", "request-1"),
                "question",
            ),
        )
        handler.answer(
            ChatRequest(
                PARTICIPANT,
                ChatRequestKey("stream-1", "request-2"),
                "follow up",
            ),
        )
        handler.answer(
            ChatRequest(
                secondParticipant,
                ChatRequestKey("stream-2", "request-2"),
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
        assertIs<EpisodeConversationSessionLease.None>(
            store.lease(PARTICIPANT, NOW, ChatServiceImpl.SESSION_IDLE_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun `ends an active thread when its turn fails`() {
        val store = RecordingSessionStore().apply {
            createAndActivate(PARTICIPANT, ChatId("existing-thread"), NOW - 1)
        }
        val client = RecordingChatGateway(turnResult = ChatGatewayResult.Failure)
        val handler = handler(store, client) { _, _ ->
            ChatContext("saved episode", hasMatches = true)
        }

        assertEquals(ChatResult.Failed, handler.answer(REQUEST))

        assertEquals(emptyList(), client.createdThreadIds)
        assertEquals(listOf("existing-thread"), client.endedThreadIds)
        assertIs<EpisodeConversationSessionLease.None>(
            store.lease(PARTICIPANT, NOW, ChatServiceImpl.SESSION_IDLE_TIMEOUT_MILLIS),
        )
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
        assertEquals(EpisodeConversationRequestStatus.FAILED, store.receipt(KEY)?.status)
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
        assertIs<EpisodeConversationSessionLease.None>(
            store.lease(PARTICIPANT, NOW, ChatServiceImpl.SESSION_IDLE_TIMEOUT_MILLIS),
        )
    }

    private fun handler(
        store: RecordingSessionStore,
        client: RecordingChatGateway,
        context: (UserId, String) -> ChatContext,
    ) = ChatServiceImpl(
        sessions = store,
        contextProvider = ChatContextSource(context),
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

        override fun close() = Unit
    }

    private class RecordingSessionStore(
        private val failAttachSession: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : EpisodeConversationSessionStore {
        private val receipts = mutableMapOf<ChatRequestKey, EpisodeConversationReceipt>()
        private val activeSessions = mutableMapOf<ChatParticipant, EpisodeConversationSession>()
        private var nextSessionId = 1

        override fun claimRequest(key: ChatRequestKey, now: Long): EpisodeConversationReceipt? {
            if (key in receipts) return null
            return receipt(key, EpisodeConversationRequestStatus.PROCESSING, now).also { receipts[key] = it }
        }

        override fun receipt(key: ChatRequestKey): EpisodeConversationReceipt? = receipts[key]

        override fun attachSession(key: ChatRequestKey, sessionId: Int, now: Long) {
            events?.add("attachSession")
            if (failAttachSession) error("attach failed")
            receipts[key] = receipts.getValue(key).copy(sessionId = sessionId, updatedAt = now)
        }

        override fun markAnswerReady(key: ChatRequestKey, answer: String, now: Long) {
            receipts[key] = receipts.getValue(key).copy(
                status = EpisodeConversationRequestStatus.ANSWER_READY,
                answerText = answer,
                updatedAt = now,
            )
        }

        override fun markFailed(key: ChatRequestKey, now: Long) {
            receipts[key] = receipts.getValue(key).copy(
                status = EpisodeConversationRequestStatus.FAILED,
                updatedAt = now,
            )
        }

        override fun createAndActivate(
            participant: ChatParticipant,
            chatId: ChatId,
            now: Long,
        ): EpisodeConversationSession = EpisodeConversationSession(
            id = nextSessionId++,
            participant = participant,
            chatId = chatId,
            createdAt = now,
            lastActiveAt = now,
        ).also {
            events?.add("createAndActivate")
            activeSessions[participant] = it
        }

        override fun lease(
            participant: ChatParticipant,
            now: Long,
            idleTimeoutMillis: Long,
        ): EpisodeConversationSessionLease {
            val session = activeSessions[participant] ?: return EpisodeConversationSessionLease.None
            if (now - session.lastActiveAt >= idleTimeoutMillis) {
                activeSessions.remove(participant)
                return EpisodeConversationSessionLease.Expired(session)
            }
            val renewed = session.copy(lastActiveAt = now)
            activeSessions[participant] = renewed
            return EpisodeConversationSessionLease.Active(renewed)
        }

        override fun clearActive(participant: ChatParticipant) {
            activeSessions.remove(participant)
        }

        override fun touch(participant: ChatParticipant, sessionId: Int, now: Long) {
            activeSessions[participant] = activeSessions.getValue(participant).copy(lastActiveAt = now)
        }

        override fun expireIdle(beforeInclusive: Long): List<EpisodeConversationSession> {
            val expired = activeSessions.values.filter { it.lastActiveAt <= beforeInclusive }
            expired.forEach { activeSessions.remove(it.participant) }
            return expired
        }

        override fun failStaleProcessing(before: Long, now: Long): Int = 0

        private fun receipt(
            key: ChatRequestKey,
            status: EpisodeConversationRequestStatus,
            now: Long,
        ) = EpisodeConversationReceipt(
            key = key,
            status = status,
            createdAt = now,
            updatedAt = now,
        )
    }

    private companion object {
        const val NOW = 1_000_000L
        val USER_ID = UserId("member-1")
        val PARTICIPANT = ChatParticipant("workspace-1", "member-1", USER_ID)
        val KEY = ChatRequestKey("stream-1", "request-1")
        val REQUEST = ChatRequest(PARTICIPANT, KEY, "question")
    }
}

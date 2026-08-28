package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnClient
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnResult
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationReceipt
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationRequestStatus
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSession
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionLease
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HandleMemoryConversationTest {
    @Test
    fun `builds and stores an answer before the inbound adapter marks it delivered`() {
        val store = RecordingSessionStore()
        val client = RecordingConversationClient()
        val seenUsers = mutableListOf<UserId>()
        val handler = handler(store, client) { userId, _ ->
            seenUsers += userId
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        val result = handler.answer(REQUEST)

        assertEquals(MemoryConversationResult.AnswerReady("grounded answer"), result)
        assertEquals(listOf(USER_ID), seenUsers)
        assertTrue(client.startedPrompt.orEmpty().contains("saved memory"))
        assertEquals(MemoryConversationRequestStatus.ANSWER_READY, store.receipt(KEY)?.status)

        handler.markDelivered(KEY, "delivery-1")

        assertEquals(MemoryConversationRequestStatus.COMPLETED, store.receipt(KEY)?.status)
        assertEquals("delivery-1", store.receipt(KEY)?.deliveryId)
    }

    @Test
    fun `returns a stored undelivered answer without invoking the model again`() {
        val store = RecordingSessionStore().apply {
            claimRequest(KEY, NOW)
            markAnswerReady(KEY, "stored answer", NOW)
        }
        val client = RecordingConversationClient()
        val handler = handler(store, client) { _, _ -> error("context must not be rebuilt") }

        val result = handler.answer(REQUEST)

        assertEquals(MemoryConversationResult.AnswerReady("stored answer"), result)
        assertEquals(null, client.startedPrompt)
    }

    @Test
    fun `returns no-match answer without starting a model conversation`() {
        val store = RecordingSessionStore()
        val client = RecordingConversationClient()
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("", hasMatches = false)
        }

        val result = handler.answer(REQUEST)

        assertEquals(
            MemoryConversationResult.AnswerReady(HandleMemoryConversation.NO_MATCH_ANSWER),
            result,
        )
        assertEquals(null, client.startedPrompt)
    }

    @Test
    fun `resumes the participant active session within its idle lease`() {
        val store = RecordingSessionStore().apply {
            createAndActivate(PARTICIPANT, "existing-thread", NOW - 1)
        }
        val client = RecordingConversationClient()
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        assertIs<MemoryConversationResult.AnswerReady>(handler.answer(REQUEST))

        assertEquals("existing-thread", client.resumedThreadId)
        assertEquals(null, client.startedPrompt)
    }

    @Test
    fun `expires an idle thread before starting a replacement`() {
        val store = RecordingSessionStore().apply {
            createAndActivate(
                PARTICIPANT,
                "expired-thread",
                NOW - HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS,
            )
        }
        val client = RecordingConversationClient()
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        assertIs<MemoryConversationResult.AnswerReady>(handler.answer(REQUEST))

        assertEquals(listOf("expired-thread"), client.endedThreadIds)
        assertEquals(null, client.resumedThreadId)
        assertTrue(client.startedPrompt != null)
    }

    @Test
    fun `keeps different participants on different conversation threads`() {
        val store = RecordingSessionStore()
        val client = RecordingConversationClient()
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }
        val secondParticipant = MemoryConversationParticipant(
            scopeId = "workspace-1",
            participantId = "member-2",
            userId = UserId("user-2"),
        )

        handler.answer(REQUEST)
        handler.answer(
            MemoryConversationRequest(
                secondParticipant,
                MemoryConversationRequestKey("stream-2", "request-1"),
                "question",
            ),
        )
        handler.answer(
            MemoryConversationRequest(
                PARTICIPANT,
                MemoryConversationRequestKey("stream-1", "request-2"),
                "follow up",
            ),
        )
        handler.answer(
            MemoryConversationRequest(
                secondParticipant,
                MemoryConversationRequestKey("stream-2", "request-2"),
                "follow up",
            ),
        )

        assertEquals(listOf("new-thread-1", "new-thread-2"), client.resumedThreadIds)
    }

    @Test
    fun `ends a newly created thread when its turn fails`() {
        val store = RecordingSessionStore()
        val client = RecordingConversationClient(turnResult = ConversationTurnResult.Failure("failed"))
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        assertEquals(MemoryConversationResult.Failed, handler.answer(REQUEST))

        assertEquals(listOf("new-thread-1"), client.endedThreadIds)
        assertIs<MemoryConversationSessionLease.None>(
            store.lease(PARTICIPANT, NOW, HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS),
        )
    }

    private fun handler(
        store: RecordingSessionStore,
        client: RecordingConversationClient,
        context: (UserId, String) -> MemoryConversationContext,
    ) = HandleMemoryConversation(
        sessions = store,
        contextProvider = MemoryConversationContextSource(context),
        conversationClient = client,
        clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
    )

    private class RecordingConversationClient(
        private val turnResult: ConversationTurnResult = ConversationTurnResult.Success("grounded answer"),
    ) : ConversationTurnClient {
        var startedPrompt: String? = null
        var resumedThreadId: String? = null
        val resumedThreadIds = mutableListOf<String>()
        val endedThreadIds = mutableListOf<String>()
        private var nextThreadId = 1

        override fun start(
            prompt: String,
            onThreadStarted: (String) -> Unit,
        ): ConversationTurnResult {
            startedPrompt = prompt
            onThreadStarted("new-thread-${nextThreadId++}")
            return turnResult
        }

        override fun resume(threadId: String, prompt: String): ConversationTurnResult {
            resumedThreadId = threadId
            resumedThreadIds += threadId
            return turnResult
        }

        override fun end(threadId: String) {
            endedThreadIds += threadId
        }
    }

    private class RecordingSessionStore : MemoryConversationSessionStore {
        private val receipts = mutableMapOf<MemoryConversationRequestKey, MemoryConversationReceipt>()
        private val activeSessions = mutableMapOf<MemoryConversationParticipant, MemoryConversationSession>()
        private var nextSessionId = 1

        override fun claimRequest(key: MemoryConversationRequestKey, now: Long): MemoryConversationReceipt? {
            if (key in receipts) return null
            return receipt(key, MemoryConversationRequestStatus.PROCESSING, now).also { receipts[key] = it }
        }

        override fun receipt(key: MemoryConversationRequestKey): MemoryConversationReceipt? = receipts[key]

        override fun attachSession(key: MemoryConversationRequestKey, sessionId: Int, now: Long) {
            receipts[key] = receipts.getValue(key).copy(sessionId = sessionId, updatedAt = now)
        }

        override fun markAnswerReady(key: MemoryConversationRequestKey, answer: String, now: Long) {
            receipts[key] = receipts.getValue(key).copy(
                status = MemoryConversationRequestStatus.ANSWER_READY,
                answerText = answer,
                updatedAt = now,
            )
        }

        override fun markCompleted(key: MemoryConversationRequestKey, deliveryId: String, now: Long) {
            receipts[key] = receipts.getValue(key).copy(
                status = MemoryConversationRequestStatus.COMPLETED,
                deliveryId = deliveryId,
                updatedAt = now,
            )
        }

        override fun markFailed(key: MemoryConversationRequestKey, now: Long) {
            receipts[key] = receipts.getValue(key).copy(
                status = MemoryConversationRequestStatus.FAILED,
                updatedAt = now,
            )
        }

        override fun createAndActivate(
            participant: MemoryConversationParticipant,
            conversationThreadId: String,
            now: Long,
        ): MemoryConversationSession = MemoryConversationSession(
            id = nextSessionId++,
            participant = participant,
            conversationThreadId = conversationThreadId,
            createdAt = now,
            lastActiveAt = now,
        ).also { activeSessions[participant] = it }

        override fun lease(
            participant: MemoryConversationParticipant,
            now: Long,
            idleTimeoutMillis: Long,
        ): MemoryConversationSessionLease {
            val session = activeSessions[participant] ?: return MemoryConversationSessionLease.None
            if (now - session.lastActiveAt >= idleTimeoutMillis) {
                activeSessions.remove(participant)
                return MemoryConversationSessionLease.Expired(session)
            }
            val renewed = session.copy(lastActiveAt = now)
            activeSessions[participant] = renewed
            return MemoryConversationSessionLease.Active(renewed)
        }

        override fun clearActive(participant: MemoryConversationParticipant) {
            activeSessions.remove(participant)
        }

        override fun touch(participant: MemoryConversationParticipant, sessionId: Int, now: Long) {
            activeSessions[participant] = activeSessions.getValue(participant).copy(lastActiveAt = now)
        }

        override fun expireIdle(beforeInclusive: Long): List<MemoryConversationSession> {
            val expired = activeSessions.values.filter { it.lastActiveAt <= beforeInclusive }
            expired.forEach { activeSessions.remove(it.participant) }
            return expired
        }

        override fun failStaleProcessing(before: Long, now: Long): Int = 0

        private fun receipt(
            key: MemoryConversationRequestKey,
            status: MemoryConversationRequestStatus,
            now: Long,
        ) = MemoryConversationReceipt(
            key = key,
            status = status,
            createdAt = now,
            updatedAt = now,
        )
    }

    private companion object {
        const val NOW = 1_000_000L
        val USER_ID = UserId("member-1")
        val PARTICIPANT = MemoryConversationParticipant("workspace-1", "member-1", USER_ID)
        val KEY = MemoryConversationRequestKey("stream-1", "request-1")
        val REQUEST = MemoryConversationRequest(PARTICIPANT, KEY, "question")
    }
}

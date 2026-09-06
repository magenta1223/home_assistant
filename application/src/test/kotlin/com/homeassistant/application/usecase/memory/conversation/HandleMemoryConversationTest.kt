package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import com.homeassistant.application.port.output.memory.conversation.ConversationThreadLifecycle
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnExecutor
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
        val events = mutableListOf<String>()
        val store = RecordingSessionStore(events = events)
        val client = RecordingConversationClient(events = events)
        val seenUsers = mutableListOf<UserId>()
        val handler = handler(store, client) { userId, _ ->
            seenUsers += userId
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        val result = handler.answer(REQUEST)

        assertEquals(MemoryConversationResult.AnswerReady("grounded answer"), result)
        assertEquals(listOf(USER_ID), seenUsers)
        assertTrue(client.executedPrompt.orEmpty().contains("saved memory"))
        assertEquals(listOf("create", "createAndActivate", "attachSession", "execute"), events)
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
        assertEquals(null, client.executedPrompt)
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
        assertEquals(null, client.executedPrompt)
    }

    @Test
    fun `executes on the participant active session within its idle lease`() {
        val store = RecordingSessionStore().apply {
            createAndActivate(PARTICIPANT, "existing-thread", NOW - 1)
        }
        val client = RecordingConversationClient()
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        assertIs<MemoryConversationResult.AnswerReady>(handler.answer(REQUEST))

        assertEquals("existing-thread", client.executedThreadId)
        assertEquals(emptyList(), client.createdThreadIds)
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
        assertEquals(listOf("new-thread-1"), client.createdThreadIds)
        assertEquals("new-thread-1", client.executedThreadId)
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

        assertEquals(
            listOf("new-thread-1", "new-thread-2", "new-thread-1", "new-thread-2"),
            client.executedThreadIds,
        )
    }

    @Test
    fun `ends a newly created thread when its turn fails`() {
        val store = RecordingSessionStore()
        val client = RecordingConversationClient(turnResult = ConversationTurnResult.Failure)
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        assertEquals(MemoryConversationResult.Failed, handler.answer(REQUEST))

        assertEquals(listOf("new-thread-1"), client.endedThreadIds)
        assertIs<MemoryConversationSessionLease.None>(
            store.lease(PARTICIPANT, NOW, HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun `ends an active thread when its turn fails`() {
        val store = RecordingSessionStore().apply {
            createAndActivate(PARTICIPANT, "existing-thread", NOW - 1)
        }
        val client = RecordingConversationClient(turnResult = ConversationTurnResult.Failure)
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        assertEquals(MemoryConversationResult.Failed, handler.answer(REQUEST))

        assertEquals(emptyList(), client.createdThreadIds)
        assertEquals(listOf("existing-thread"), client.endedThreadIds)
        assertIs<MemoryConversationSessionLease.None>(
            store.lease(PARTICIPANT, NOW, HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun `does not execute a turn when thread creation fails`() {
        val store = RecordingSessionStore()
        val client = RecordingConversationClient(createFailure = IllegalStateException("create failed"))
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        assertEquals(MemoryConversationResult.Failed, handler.answer(REQUEST))

        assertEquals(null, client.executedThreadId)
        assertEquals(MemoryConversationRequestStatus.FAILED, store.receipt(KEY)?.status)
    }

    @Test
    fun `ends a created thread when session attachment fails`() {
        val store = RecordingSessionStore(failAttachSession = true)
        val client = RecordingConversationClient()
        val handler = handler(store, client) { _, _ ->
            MemoryConversationContext("saved memory", hasMatches = true)
        }

        assertEquals(MemoryConversationResult.Failed, handler.answer(REQUEST))

        assertEquals(listOf("new-thread-1"), client.endedThreadIds)
        assertEquals(null, client.executedThreadId)
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
        threadLifecycle = client,
        turnExecutor = client,
        clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
    )

    private class RecordingConversationClient(
        private val turnResult: ConversationTurnResult = ConversationTurnResult.Success("grounded answer"),
        private val createFailure: Exception? = null,
        private val events: MutableList<String>? = null,
    ) : ConversationThreadLifecycle, ConversationTurnExecutor {
        var executedPrompt: String? = null
        var executedThreadId: String? = null
        val createdThreadIds = mutableListOf<String>()
        val executedThreadIds = mutableListOf<String>()
        val endedThreadIds = mutableListOf<String>()
        private var nextThreadId = 1

        override fun create(): String {
            createFailure?.let { throw it }
            events?.add("create")
            return "new-thread-${nextThreadId++}".also(createdThreadIds::add)
        }

        override fun execute(threadId: String, prompt: String): ConversationTurnResult {
            events?.add("execute")
            executedPrompt = prompt
            executedThreadId = threadId
            executedThreadIds += threadId
            return turnResult
        }

        override fun end(threadId: String) {
            endedThreadIds += threadId
        }
    }

    private class RecordingSessionStore(
        private val failAttachSession: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : MemoryConversationSessionStore {
        private val receipts = mutableMapOf<MemoryConversationRequestKey, MemoryConversationReceipt>()
        private val activeSessions = mutableMapOf<MemoryConversationParticipant, MemoryConversationSession>()
        private var nextSessionId = 1

        override fun claimRequest(key: MemoryConversationRequestKey, now: Long): MemoryConversationReceipt? {
            if (key in receipts) return null
            return receipt(key, MemoryConversationRequestStatus.PROCESSING, now).also { receipts[key] = it }
        }

        override fun receipt(key: MemoryConversationRequestKey): MemoryConversationReceipt? = receipts[key]

        override fun attachSession(key: MemoryConversationRequestKey, sessionId: Int, now: Long) {
            events?.add("attachSession")
            if (failAttachSession) error("attach failed")
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
        ).also {
            events?.add("createAndActivate")
            activeSessions[participant] = it
        }

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

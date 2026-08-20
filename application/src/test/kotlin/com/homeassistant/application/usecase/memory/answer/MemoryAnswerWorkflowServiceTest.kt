package com.homeassistant.application.usecase.memory.answer

import com.homeassistant.application.port.input.memory.answer.CompleteUserRegistrationRequest
import com.homeassistant.application.port.input.memory.answer.ConversationRequestKey
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationStartResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationValidationResult
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.identity.RegisterUserRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import com.homeassistant.application.port.output.memory.answer.PendingRegistrationQuestionStore
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MemoryAnswerWorkflowServiceTest {
    @Test
    fun `registered identity is resolved before the memory conversation is called`() {
        val users = InMemoryUsers(registered = true)
        val memory = RecordingMemoryConversation()
        val handler = handler(users, InMemoryPendingConversations(), memory)

        val result = handler.receive(MESSAGE)

        assertEquals(MemoryAnswerResult.AnswerReady("answer"), result)
        val request = memory.requests.single()
        assertEquals(USER_ID, request.participant.userId)
        assertEquals(IDENTITY.scopeId, request.participant.scopeId)
        assertEquals(IDENTITY.participantId, request.participant.participantId)
        assertEquals(KEY, request.key)
    }

    @Test
    fun `first unregistered question is persisted and later questions remain pending`() {
        val pending = InMemoryPendingConversations()
        val handler = handler(InMemoryUsers(), pending, RecordingMemoryConversation())

        assertEquals(MemoryAnswerResult.RegistrationRequired, handler.receive(MESSAGE))
        assertEquals(
            MemoryAnswerResult.RegistrationPending,
            handler.receive(MESSAGE.copy(key = ConversationRequestKey("stream-1", "request-2"))),
        )

        assertEquals(MESSAGE, pending.find(IDENTITY))
        assertEquals(UserRegistrationStartResult.Ready(KEY), handler.beginRegistration(IDENTITY))
    }

    @Test
    fun `failed registration prompt delivery releases the question for a Slack retry`() {
        val pending = InMemoryPendingConversations()
        val handler = handler(InMemoryUsers(), pending, RecordingMemoryConversation())
        handler.receive(MESSAGE)

        handler.registrationPromptDeliveryFailed(IDENTITY, KEY)

        assertNull(pending.find(IDENTITY))
        assertEquals(MemoryAnswerResult.RegistrationRequired, handler.receive(MESSAGE))
    }

    @Test
    fun `registration is validated normalized and resumes the persisted question`() {
        val events = mutableListOf<String>()
        val users = InMemoryUsers()
        val pending = InMemoryPendingConversations()
        val memory = RecordingMemoryConversation(onAnswer = { events += "answer" })
        val handler = handler(users, pending, memory)
        handler.receive(MESSAGE)

        assertEquals(
            UserRegistrationValidationResult.Valid("홍길동"),
            handler.validateRegistration(" 홍길동 "),
        )
        val result = handler.completeRegistration(
            CompleteUserRegistrationRequest(IDENTITY, " 홍길동 "),
        ) { notice ->
            events += "registered"
            assertEquals("홍길동", notice.displayName)
            assertEquals(KEY, notice.replyKey)
        }

        assertEquals(listOf("registered", "answer"), events)
        assertEquals(
            UserRegistrationResult.Completed(
                KEY,
                MemoryAnswerResult.AnswerReady("answer"),
            ),
            result,
        )
        assertEquals("홍길동", users.find(IDENTITY)?.displayName)
        assertNull(pending.find(IDENTITY))
    }

    @Test
    fun `invalid registration is rejected without changing member or pending question`() {
        val users = InMemoryUsers()
        val pending = InMemoryPendingConversations()
        val handler = handler(users, pending, RecordingMemoryConversation())
        handler.receive(MESSAGE)

        assertEquals(UserRegistrationValidationResult.Invalid, handler.validateRegistration("   "))
        assertEquals(
            UserRegistrationResult.InvalidDisplayName,
            handler.completeRegistration(CompleteUserRegistrationRequest(IDENTITY, "x".repeat(51))) {},
        )
        assertNull(users.find(IDENTITY))
        assertEquals(MESSAGE, pending.find(IDENTITY))
    }

    @Test
    fun `registration remains available when memory answers are unavailable`() {
        val users = InMemoryUsers()
        val pending = InMemoryPendingConversations()
        val handler = handler(users, pending, null)
        handler.receive(MESSAGE)

        val result = handler.completeRegistration(
            CompleteUserRegistrationRequest(IDENTITY, "이름"),
        ) {}

        assertIs<UserRegistrationResult.Completed>(result)
        assertEquals(MemoryAnswerResult.Unavailable, result.conversation)
        assertEquals("이름", users.find(IDENTITY)?.displayName)
        assertNull(pending.find(IDENTITY))
    }

    private fun handler(
        users: UserRegistry,
        pending: PendingRegistrationQuestionStore,
        memory: MemoryConversation?,
    ) = MemoryAnswerWorkflowService(
        users = users,
        pendingQuestions = pending,
        memoryConversation = memory,
        clock = Clock.fixed(Instant.ofEpochMilli(123L), ZoneOffset.UTC),
    )

    private class InMemoryUsers(registered: Boolean = false) : UserRegistry {
        private val users = mutableMapOf<ConversationIdentity, RegisteredUser>()

        init {
            if (registered) users[IDENTITY] = RegisteredUser(USER_ID, "기존 사용자")
        }

        override fun find(identity: ConversationIdentity): RegisteredUser? = users[identity]

        override fun register(request: RegisterUserRequest): RegisteredUser =
            users.getOrPut(request.identity) {
                RegisteredUser(USER_ID, RegisteredUser.normalizeDisplayName(request.displayName))
            }

        override fun list(): List<RegisteredUser> = users.values.toList()
    }

    private class InMemoryPendingConversations : PendingRegistrationQuestionStore {
        private val requests = mutableMapOf<ConversationIdentity, MemoryAnswerRequest>()

        override fun rememberFirst(request: MemoryAnswerRequest, now: Long): Boolean =
            requests.putIfAbsent(request.identity, request) == null

        override fun find(identity: ConversationIdentity): MemoryAnswerRequest? = requests[identity]

        override fun remove(identity: ConversationIdentity) {
            requests.remove(identity)
        }
    }

    private class RecordingMemoryConversation(
        private val onAnswer: () -> Unit = {},
    ) : MemoryConversation {
        val requests = mutableListOf<MemoryConversationRequest>()

        override fun answer(request: MemoryConversationRequest): MemoryConversationResult {
            requests += request
            onAnswer()
            return MemoryConversationResult.AnswerReady("answer")
        }

        override fun markDelivered(key: ConversationRequestKey, deliveryId: String) = Unit
    }

    private companion object {
        val IDENTITY = ConversationIdentity("scope-1", "participant-1")
        val USER_ID = UserId("member-1")
        val KEY = ConversationRequestKey("stream-1", "request-1")
        val MESSAGE = MemoryAnswerRequest(IDENTITY, KEY, "question")
    }
}

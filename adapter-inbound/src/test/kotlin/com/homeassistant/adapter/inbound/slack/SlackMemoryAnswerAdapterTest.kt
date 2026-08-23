package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.memory.answer.CompleteUserRegistrationRequest
import com.homeassistant.application.port.input.memory.answer.ConversationRequestKey
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerWorkflow
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationNotice
import com.homeassistant.application.port.input.memory.answer.UserRegistrationResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationStartResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationValidationResult
import com.homeassistant.application.port.input.identity.ConversationIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackMemoryAnswerAdapterTest {
    @Test
    fun `Slack message is mapped to the application conversation and the answer is delivered`() {
        val conversation = RecordingMemoryAnswerWorkflow(
            receiveResult = MemoryAnswerResult.AnswerReady("answer"),
        )
        val slack = RecordingSlackClient()
        val service = service(conversation, slack)

        service.handle(MESSAGE)

        assertEquals(
            MemoryAnswerRequest(
                identity = ConversationIdentity("team-1", "slack-1"),
                key = KEY,
                question = "question",
            ),
            conversation.messages.single(),
        )
        assertEquals(listOf("channel-1" to "answer"), slack.messages.map { it.channelId to it.text })
        assertEquals(listOf(KEY to "response-1"), conversation.deliveries)
    }

    @Test
    fun `registration UI renders application outcomes and completes the pending answer`() {
        val conversation = RecordingMemoryAnswerWorkflow(
            receiveResult = MemoryAnswerResult.RegistrationRequired,
            registrationStart = UserRegistrationStartResult.Ready(KEY),
            registrationResult = UserRegistrationResult.Completed(
                KEY,
                MemoryAnswerResult.AnswerReady("answer"),
            ),
        )
        val slack = RecordingSlackClient()
        val service = service(conversation, slack)

        service.handle(MESSAGE)
        assertTrue(slack.messages.single().blocks.isNotEmpty())

        service.openRegistrationModal("team-1", "slack-1", "trigger-1")
        assertEquals("trigger-1", slack.modals.single().first)
        assertEquals("channel-1", slack.modals.single().second["private_metadata"])

        assertTrue(service.submitRegistration("team-1", "slack-1", "channel-1", " 홍길동 "))

        assertEquals("홍길동", conversation.registrations.single().displayName)
        assertEquals(
            listOf(
                "처음 오셨네요. 답변을 받으려면 사용자 등록을 완료해주세요.",
                "홍길동님, 등록되었습니다.",
                "answer",
            ),
            slack.messages.map(PostedMessage::text),
        )
    }

    @Test
    fun `registration pending outcome does not duplicate the prompt`() {
        val conversation = RecordingMemoryAnswerWorkflow(
            receiveResult = MemoryAnswerResult.RegistrationPending,
        )
        val slack = RecordingSlackClient()

        service(conversation, slack).handle(MESSAGE)

        assertEquals(emptyList(), slack.messages)
    }

    @Test
    fun `registration prompt delivery failure is returned to the application`() {
        val conversation = RecordingMemoryAnswerWorkflow(
            receiveResult = MemoryAnswerResult.RegistrationRequired,
        )
        val service = service(conversation, RecordingSlackClient(failPosts = true))

        service.handle(MESSAGE)

        assertEquals(listOf(ConversationIdentity("team-1", "slack-1") to KEY), conversation.promptFailures)
    }

    @Test
    fun `invalid registration name is rejected by the application contract`() {
        val conversation = RecordingMemoryAnswerWorkflow(
            registrationValidation = UserRegistrationValidationResult.Invalid,
        )
        val service = service(conversation, RecordingSlackClient())

        assertFalse(service.submitRegistration("team-1", "slack-1", "channel-1", "   "))
        assertEquals(listOf("   "), conversation.validatedNames)
    }

    @Test
    fun `unavailable memory conversation is rendered as a retryable Slack error`() {
        val slack = RecordingSlackClient()
        val service = service(
            RecordingMemoryAnswerWorkflow(receiveResult = MemoryAnswerResult.Unavailable),
            slack,
        )

        service.handle(MESSAGE)

        assertEquals("답변 처리에 실패했습니다. 새 메시지로 다시 시도해주세요.", slack.messages.single().text)
    }

    @Test
    fun `ignores messages from another Slack workspace before calling the application`() {
        val conversation = RecordingMemoryAnswerWorkflow()
        val slack = RecordingSlackClient()
        val service = service(conversation, slack)

        service.handle(MESSAGE.copy(teamId = "other-team"))

        assertEquals(emptyList(), conversation.messages)
        assertEquals(emptyList(), slack.messages)
    }

    private fun service(
        conversation: MemoryAnswerWorkflow,
        slack: SlackClient,
    ): SlackMemoryAnswerAdapter = SlackMemoryAnswerAdapter(
        configuredTeamId = "team-1",
        memoryAnswerWorkflow = conversation,
        slack = slack,
        executor = Runnable::run,
    )

    private class RecordingMemoryAnswerWorkflow(
        private val receiveResult: MemoryAnswerResult = MemoryAnswerResult.AlreadyHandled,
        private val registrationStart: UserRegistrationStartResult =
            UserRegistrationStartResult.NoPendingQuestion,
        private val registrationValidation: UserRegistrationValidationResult =
            UserRegistrationValidationResult.Valid("홍길동"),
        private val registrationResult: UserRegistrationResult = UserRegistrationResult.NoPendingQuestion,
    ) : MemoryAnswerWorkflow {
        val messages = mutableListOf<MemoryAnswerRequest>()
        val validatedNames = mutableListOf<String>()
        val registrations = mutableListOf<CompleteUserRegistrationRequest>()
        val deliveries = mutableListOf<Pair<ConversationRequestKey, String>>()
        val promptFailures = mutableListOf<Pair<ConversationIdentity, ConversationRequestKey>>()

        override fun receive(request: MemoryAnswerRequest): MemoryAnswerResult {
            messages += request
            return receiveResult
        }

        override fun beginRegistration(identity: ConversationIdentity): UserRegistrationStartResult =
            registrationStart

        override fun validateRegistration(displayName: String): UserRegistrationValidationResult {
            validatedNames += displayName
            return registrationValidation
        }

        override fun completeRegistration(
            request: CompleteUserRegistrationRequest,
            onRegistered: (UserRegistrationNotice) -> Unit,
        ): UserRegistrationResult {
            registrations += request
            if (registrationResult is UserRegistrationResult.Completed) {
                onRegistered(UserRegistrationNotice(request.displayName, registrationResult.replyKey))
            }
            return registrationResult
        }

        override fun markDelivered(key: ConversationRequestKey, deliveryId: String) {
            deliveries += key to deliveryId
        }

        override fun registrationPromptDeliveryFailed(
            identity: ConversationIdentity,
            key: ConversationRequestKey,
        ) {
            promptFailures += identity to key
        }
    }

    private class RecordingSlackClient(
        private val failPosts: Boolean = false,
    ) : SlackClient {
        val messages = mutableListOf<PostedMessage>()
        val modals = mutableListOf<Pair<String, Map<String, Any>>>()

        override fun postMessage(
            channelId: String,
            text: String,
            blocks: List<Map<String, Any>>,
            threadTs: String?,
        ): SlackMessageDelivery {
            if (failPosts) error("delivery failed")
            messages += PostedMessage(channelId, text, blocks)
            return SlackMessageDelivery("response-1")
        }

        override fun openModal(triggerId: String, view: Map<String, Any>) {
            modals += triggerId to view
        }

        override fun respond(responseUrl: String, text: String) = Unit
    }

    private data class PostedMessage(
        val channelId: String,
        val text: String,
        val blocks: List<Map<String, Any>>,
    )

    private companion object {
        val KEY = ConversationRequestKey("channel-1", "message-1")
        val MESSAGE = SlackDirectMessage(
            teamId = "team-1",
            slackUserId = "slack-1",
            channelId = "channel-1",
            messageTs = "message-1",
            text = "question",
        )
    }
}

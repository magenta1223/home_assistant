package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.HouseholdMembers
import com.homeassistant.application.port.input.identity.RegisterHouseholdMemberRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import com.homeassistant.domain.identity.HouseholdMember
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackConversationServiceTest {
    @Test
    fun `registered Slack member is mapped to a memory conversation and receives the answer`() {
        val conversation = RecordingMemoryConversation(MemoryConversationResult.AnswerReady("answer"))
        val slack = RecordingSlackClient()
        val members = InMemoryHouseholdMembers(registered = true)
        val service = service(members, conversation, slack)

        service.handle(MESSAGE)

        val request = conversation.requests.single()
        assertEquals("member-1", request.participant.userId.value)
        assertEquals("team-1", request.participant.scopeId)
        assertEquals("slack-1", request.participant.participantId)
        assertEquals("channel-1", request.key.streamId)
        assertEquals("message-1", request.key.requestId)
        assertEquals("question", request.question)
        assertEquals(listOf("channel-1" to "answer"), slack.messages.map { it.channelId to it.text })
        assertEquals(listOf(request.key to "response-1"), conversation.deliveries)
    }

    @Test
    fun `first message prompts registration and registration resumes that question`() {
        val conversation = RecordingMemoryConversation(MemoryConversationResult.AnswerReady("answer"))
        val slack = RecordingSlackClient()
        val members = InMemoryHouseholdMembers()
        val service = service(members, conversation, slack)

        service.handle(MESSAGE)

        assertEquals(emptyList(), conversation.requests)
        assertEquals(1, slack.messages.size)
        assertTrue(slack.messages.single().blocks.isNotEmpty())
        assertTrue(slack.messages.single().text.contains("등록"))

        service.openRegistrationModal("team-1", "slack-1", "channel-1", "trigger-1")
        assertEquals("trigger-1", slack.modals.single().first)
        assertEquals("channel-1", slack.modals.single().second["private_metadata"])

        assertTrue(service.submitRegistration("team-1", "slack-1", "channel-1", " 홍길동 "))

        assertEquals("홍길동", members.find(MESSAGE.identity())?.displayName)
        assertEquals("question", conversation.requests.single().question)
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
    fun `repeated messages while unregistered retain the first question and prompt only once`() {
        val conversation = RecordingMemoryConversation(MemoryConversationResult.AnswerReady("answer"))
        val slack = RecordingSlackClient()
        val service = service(InMemoryHouseholdMembers(), conversation, slack)

        service.handle(MESSAGE)
        service.handle(MESSAGE.copy(messageTs = "message-2", text = "second question"))
        service.submitRegistration("team-1", "slack-1", "channel-1", "이름")

        assertEquals(3, slack.messages.size)
        assertEquals("question", conversation.requests.single().question)
    }

    @Test
    fun `invalid registration name is rejected`() {
        val service = service(
            InMemoryHouseholdMembers(),
            RecordingMemoryConversation(MemoryConversationResult.AnswerReady("answer")),
            RecordingSlackClient(),
        )

        assertFalse(service.submitRegistration("team-1", "slack-1", "channel-1", "   "))
        assertFalse(service.submitRegistration("team-1", "slack-1", "channel-1", "x".repeat(51)))
    }

    @Test
    fun `registration remains available when memory answers are unavailable`() {
        val slack = RecordingSlackClient()
        val members = InMemoryHouseholdMembers()
        val service = service(members, null, slack)

        service.handle(MESSAGE)
        service.submitRegistration("team-1", "slack-1", "channel-1", "이름")

        assertEquals("이름", members.find(MESSAGE.identity())?.displayName)
        assertEquals("답변 처리에 실패했습니다. 새 메시지로 다시 시도해주세요.", slack.messages.last().text)
    }

    @Test
    fun `posts a retryable Slack error when memory conversation fails`() {
        val conversation = RecordingMemoryConversation(MemoryConversationResult.Failed)
        val slack = RecordingSlackClient()
        val service = service(InMemoryHouseholdMembers(registered = true), conversation, slack)

        service.handle(MESSAGE)

        assertEquals(1, slack.messages.size)
        assertEquals("channel-1", slack.messages.single().channelId)
        assertEquals("답변 처리에 실패했습니다. 새 메시지로 다시 시도해주세요.", slack.messages.single().text)
        assertEquals(emptyList(), conversation.deliveries)
    }

    @Test
    fun `ignores messages from another Slack workspace`() {
        val conversation = RecordingMemoryConversation(MemoryConversationResult.AnswerReady("answer"))
        val slack = RecordingSlackClient()
        val service = service(InMemoryHouseholdMembers(), conversation, slack)

        service.handle(MESSAGE.copy(teamId = "other-team"))

        assertEquals(emptyList(), conversation.requests)
        assertEquals(emptyList(), slack.messages)
    }

    private fun service(
        members: HouseholdMembers,
        conversation: MemoryConversation?,
        slack: SlackClient,
    ): SlackConversationService = SlackConversationService(
        configuredTeamId = "team-1",
        householdMembers = members,
        memoryConversation = conversation,
        slack = slack,
        executor = Runnable::run,
    )

    private class InMemoryHouseholdMembers(registered: Boolean = false) : HouseholdMembers {
        private val members = mutableMapOf<ConversationIdentity, HouseholdMember>()

        init {
            if (registered) members[MESSAGE.identity()] = HouseholdMember(UserId("member-1"), "기존 사용자")
        }

        override fun find(identity: ConversationIdentity): HouseholdMember? = members[identity]

        override fun register(request: RegisterHouseholdMemberRequest): HouseholdMember =
            members.getOrPut(request.identity) {
                HouseholdMember(UserId("member-1"), request.displayName.trim())
            }

        override fun list(): List<HouseholdMember> = members.values.toList()
    }

    private class RecordingMemoryConversation(
        private val result: MemoryConversationResult,
    ) : MemoryConversation {
        val requests = mutableListOf<MemoryConversationRequest>()
        val deliveries = mutableListOf<Pair<MemoryConversationRequestKey, String>>()

        override fun answer(request: MemoryConversationRequest): MemoryConversationResult {
            requests += request
            return result
        }

        override fun markDelivered(key: MemoryConversationRequestKey, deliveryId: String) {
            deliveries += key to deliveryId
        }
    }

    private class RecordingSlackClient : SlackClient {
        val messages = mutableListOf<PostedMessage>()
        val modals = mutableListOf<Pair<String, Map<String, Any>>>()

        override fun postMessage(
            channelId: String,
            text: String,
            blocks: List<Map<String, Any>>,
            threadTs: String?,
        ): SlackMessageDelivery {
            messages += PostedMessage(channelId, text, blocks)
            return SlackMessageDelivery("response-1")
        }

        override fun openModal(triggerId: String, view: Map<String, Any>) {
            modals += triggerId to view
        }
    }

    private data class PostedMessage(
        val channelId: String,
        val text: String,
        val blocks: List<Map<String, Any>>,
    )

    private companion object {
        val MESSAGE = SlackDirectMessage(
            teamId = "team-1",
            slackUserId = "slack-1",
            channelId = "channel-1",
            messageTs = "message-1",
            text = "question",
        )

        fun SlackDirectMessage.identity() = ConversationIdentity(teamId, slackUserId)
    }
}

package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackConversationServiceTest {
    @Test
    fun `maps a Slack member to a memory conversation and delivers the returned answer`() {
        val conversation = RecordingMemoryConversation(MemoryConversationResult.AnswerReady("answer"))
        val slack = RecordingSlackClient()
        val service = SlackConversationService(identityDirectory(), conversation, slack, Runnable::run)

        service.handle(MESSAGE)

        val request = conversation.requests.single()
        assertEquals("member-1", request.participant.userId.value)
        assertEquals("team-1", request.participant.scopeId)
        assertEquals("slack-1", request.participant.participantId)
        assertEquals("channel-1", request.key.streamId)
        assertEquals("message-1", request.key.requestId)
        assertEquals("question", request.question)
        assertEquals(listOf("channel-1" to "answer"), slack.messages)
        assertEquals(listOf(request.key to "response-1"), conversation.deliveries)
    }

    @Test
    fun `posts a retryable Slack error when memory conversation fails`() {
        val conversation = RecordingMemoryConversation(MemoryConversationResult.Failed)
        val slack = RecordingSlackClient()
        val service = SlackConversationService(identityDirectory(), conversation, slack, Runnable::run)

        service.handle(MESSAGE)

        assertEquals(1, slack.messages.size)
        assertEquals("channel-1", slack.messages.single().first)
        assertEquals("답변 처리에 실패했습니다. 새 메시지로 다시 시도해주세요.", slack.messages.single().second)
        assertEquals(emptyList(), conversation.deliveries)
    }

    @Test
    fun `ignores messages from an unmapped Slack member`() {
        val conversation = RecordingMemoryConversation(MemoryConversationResult.AnswerReady("answer"))
        val slack = RecordingSlackClient()
        val service = SlackConversationService(identityDirectory(), conversation, slack, Runnable::run)

        service.handle(MESSAGE.copy(slackUserId = "unknown"))

        assertEquals(emptyList(), conversation.requests)
        assertEquals(emptyList(), slack.messages)
    }

    private fun identityDirectory(): SlackIdentityDirectory = SlackIdentityDirectoryFactory.fromJson(
        configuredTeamId = "team-1",
        json = """[{"teamId":"team-1","slackUserId":"slack-1","userId":"member-1"}]""",
    )

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
        val messages = mutableListOf<Pair<String, String>>()

        override fun postMessage(
            channelId: String,
            text: String,
            blocks: List<Map<String, Any>>,
            threadTs: String?,
        ): SlackMessageDelivery {
            messages += channelId to text
            return SlackMessageDelivery("response-1")
        }
    }

    private companion object {
        val MESSAGE = SlackDirectMessage(
            teamId = "team-1",
            slackUserId = "slack-1",
            channelId = "channel-1",
            messageTs = "message-1",
            text = "question",
        )
    }
}

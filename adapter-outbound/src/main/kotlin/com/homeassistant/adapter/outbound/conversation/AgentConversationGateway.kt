package com.homeassistant.adapter.outbound.conversation

import com.homeassistant.application.port.output.memory.conversation.ConversationGateway
import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.application.port.output.memory.conversation.ConversationReply
import com.homeassistant.llm.conversation.ConversationClient
import org.slf4j.LoggerFactory

/** Adapts an agent backend [ConversationClient] to the application conversation port. */
class AgentConversationGateway(
    private val client: ConversationClient,
) : ConversationGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun begin(): Result<ConversationId> = client.begin().map(::ConversationId)

    override fun continueConversation(conversationId: ConversationId, prompt: String): ConversationReply =
        client.send(conversationId.value, prompt).fold(
            onSuccess = ConversationReply::Success,
            onFailure = { error ->
                log.warn("Conversation turn failed category={}", error.message ?: error.javaClass.simpleName)
                ConversationReply.Failure
            },
        )

    override fun end(conversationId: ConversationId) {
        client.end(conversationId.value)
    }

    override fun close() {
        client.close()
    }
}

package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationGateway
import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.application.port.output.memory.conversation.ConversationReply

internal object NoOpConversationGateway : ConversationGateway {
    override fun begin(): Result<ConversationId> =
        Result.failure(IllegalStateException("Conversation gateway is unavailable"))

    override fun continueConversation(
        conversationId: ConversationId,
        prompt: String,
    ): ConversationReply = ConversationReply.Failure

    override fun end(conversationId: ConversationId) = Unit

    override fun close() = Unit
}

package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationGateway
import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.application.port.output.memory.conversation.ConversationReply
import com.homeassistant.codex.conversation.CodexAppServer
import com.homeassistant.codex.conversation.CodexThreadId
import org.slf4j.LoggerFactory

internal class CodexConversationGateway(
    private val appServer: CodexAppServer,
) : ConversationGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun begin(): Result<ConversationId> =
        appServer.createThread().map { ConversationId(it.value) }

    override fun continueConversation(
        conversationId: ConversationId,
        prompt: String,
    ): ConversationReply = appServer.executeTurn(CodexThreadId(conversationId.value), prompt).fold(
        onSuccess = ConversationReply::Success,
        onFailure = { error ->
            log.warn("Conversation turn failed category={}", error.message ?: error.javaClass.simpleName)
            ConversationReply.Failure
        },
    )

    override fun end(conversationId: ConversationId) {
        appServer.releaseThread(CodexThreadId(conversationId.value))
    }

    override fun close() {
        appServer.close()
    }
}

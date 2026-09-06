package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationTurnResult
import com.homeassistant.codex.conversation.ConversationClient
import org.slf4j.LoggerFactory

internal class IntegrationConversationAdapter(
    private val client: ConversationClient,
) : ManagedConversationAdapter {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(): Boolean = client.isAvailable()

    override fun startServer(): Boolean = client.startServer()

    override fun create(): String = client.create().getOrElse { error ->
        log.warn("Conversation thread creation failed category={}", error.message ?: error.javaClass.simpleName)
        throw error
    }

    override fun execute(threadId: String, prompt: String): ConversationTurnResult =
        client.execute(threadId, prompt).fold(
            onSuccess = ConversationTurnResult::Success,
            onFailure = { error ->
                log.warn("Conversation turn failed category={}", error.message ?: error.javaClass.simpleName)
                ConversationTurnResult.Failure
            },
        )

    override fun end(threadId: String) {
        client.end(threadId)
    }

    override fun close() {
        client.close()
    }
}

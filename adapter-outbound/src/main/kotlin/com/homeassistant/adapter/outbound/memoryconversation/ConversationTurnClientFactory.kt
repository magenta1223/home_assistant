package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.codex.conversation.ConversationClient
import com.homeassistant.codex.conversation.ConversationClientFactory
import java.time.Duration

object ConversationTurnClientFactory {
    fun create(timeout: Duration): ManagedConversationTurnClient? =
        ConversationClientFactory.create(timeout)?.let(::IntegrationConversationTurnClient)

    internal fun create(client: ConversationClient): ManagedConversationTurnClient =
        IntegrationConversationTurnClient(client)
}

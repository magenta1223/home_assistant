package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.codex.conversation.ConversationClient
import com.homeassistant.codex.conversation.ConversationClientFactory as IntegrationConversationClientFactory
import java.time.Duration

object ConversationAdapterFactory {
    fun create(timeout: Duration): ManagedConversationAdapter? =
        IntegrationConversationClientFactory.create(timeout)?.let(::IntegrationConversationAdapter)

    internal fun create(client: ConversationClient): ManagedConversationAdapter =
        IntegrationConversationAdapter(client)
}

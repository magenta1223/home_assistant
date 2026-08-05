package com.homeassistant.adapter.inbound.slack

object CodexConversationClientFactory {
    fun create(config: CodexConversationConfig): CodexConversationClient =
        ProcessCodexConversationClient(config)
}

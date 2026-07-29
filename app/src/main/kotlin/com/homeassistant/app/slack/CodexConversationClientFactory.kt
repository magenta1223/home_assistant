package com.homeassistant.app.slack

object CodexConversationClientFactory {
    fun create(config: CodexConversationConfig): CodexConversationClient =
        ProcessCodexConversationClient(config)
}

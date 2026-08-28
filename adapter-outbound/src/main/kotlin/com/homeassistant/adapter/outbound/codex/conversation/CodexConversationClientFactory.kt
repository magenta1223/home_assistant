package com.homeassistant.adapter.outbound.codex.conversation

object CodexConversationClientFactory {
    fun create(config: CodexConversationConfig): CodexConversationClient =
        CodexAppServerConversationClient(config)
}

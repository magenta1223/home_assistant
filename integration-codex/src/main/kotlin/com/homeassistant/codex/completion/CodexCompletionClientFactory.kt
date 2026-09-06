package com.homeassistant.codex.completion

object CodexCompletionClientFactory {
    fun create(): CompletionClient {
        return CodexCliClient()
    }
}

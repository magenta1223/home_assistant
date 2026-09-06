package com.homeassistant.codex.completion

object CodexCompletionClientFactory {
    fun create(): CodexCompletionClient {
        return CodexCliClient()
    }
}
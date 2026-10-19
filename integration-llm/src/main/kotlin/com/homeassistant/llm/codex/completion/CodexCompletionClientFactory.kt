package com.homeassistant.llm.codex.completion

import com.homeassistant.llm.completion.CompletionClient

internal object CodexCompletionClientFactory {
    fun create(): CompletionClient {
        return CodexCliClient()
    }
}

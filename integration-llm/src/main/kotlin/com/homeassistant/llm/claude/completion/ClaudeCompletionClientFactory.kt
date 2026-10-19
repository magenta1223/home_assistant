package com.homeassistant.llm.claude.completion

import com.homeassistant.llm.completion.CompletionClient

internal object ClaudeCompletionClientFactory {
    fun create(): CompletionClient = ClaudeCliCompletionClient()
}

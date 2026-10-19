package com.homeassistant.llm.codex.conversation

internal class CodexConversationException(val category: String) : RuntimeException(category)

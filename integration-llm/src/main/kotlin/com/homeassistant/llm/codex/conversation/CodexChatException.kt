package com.homeassistant.llm.codex.conversation

internal class CodexChatException(val category: String) : RuntimeException(category)

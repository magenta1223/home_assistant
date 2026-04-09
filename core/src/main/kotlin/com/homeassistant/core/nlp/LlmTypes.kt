package com.homeassistant.core.nlp

import com.homeassistant.core.tools.ToolCallSpec

@JvmInline value class SystemPrompt(val value: String)
@JvmInline value class LlmRawResponse(val value: String)

sealed class LlmResponse {
    data class Text(val content: LlmRawResponse) : LlmResponse()
    data class ToolCall(val spec: ToolCallSpec) : LlmResponse()
}
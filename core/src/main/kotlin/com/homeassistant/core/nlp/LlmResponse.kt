package com.homeassistant.core.nlp

import com.homeassistant.core.tools.ToolCallSpec

sealed class LlmResponse {
    data class Text(val content: String) : LlmResponse()
    data class ToolCall(val spec: ToolCallSpec) : LlmResponse()
}

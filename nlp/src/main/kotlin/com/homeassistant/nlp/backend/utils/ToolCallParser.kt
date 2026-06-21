package com.homeassistant.nlp.backend.utils

import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.utils.JsonSerializer.parseToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


fun parseToolCallOrText(content: String): LlmResponse {
    return try {
        val root = content.parseToJsonElement().jsonObject
        val toolCall = root["tool_call"]?.jsonObject ?: return LlmResponse.Text(content)
        val name = toolCall["name"]?.jsonPrimitive?.content ?: return LlmResponse.Text(content)
        val arguments = toolCall["arguments"] ?: JsonObject(emptyMap())
        LlmResponse.ToolCall(ToolCallSpec(name, arguments.toString()))
    } catch (_: Exception) {
        LlmResponse.Text(content)
    }
}

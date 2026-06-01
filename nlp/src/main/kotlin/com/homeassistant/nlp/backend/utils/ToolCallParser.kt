package com.homeassistant.nlp.backend.utils

import com.homeassistant.core.nlp.LlmRawResponse
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val toolCallJson = Json { ignoreUnknownKeys = true }

fun parseToolCallOrText(content: String): LlmResponse {
    return try {
        val root = toolCallJson.parseToJsonElement(content).jsonObject
        val toolCall = root["tool_call"]?.jsonObject ?: return LlmResponse.Text(LlmRawResponse(content))
        val name = toolCall["name"]?.jsonPrimitive?.content ?: return LlmResponse.Text(LlmRawResponse(content))
        val arguments = toolCall["arguments"] ?: JsonObject(emptyMap())
        LlmResponse.ToolCall(ToolCallSpec(ToolName(name), ToolArguments(arguments.toString())))
    } catch (_: Exception) {
        LlmResponse.Text(LlmRawResponse(content))
    }
}

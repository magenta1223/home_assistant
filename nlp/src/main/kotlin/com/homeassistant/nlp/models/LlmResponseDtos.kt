package com.homeassistant.nlp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class IntentAnalysisDto(
    val intent: String,
    val contexts: List<ContextSpecDto> = emptyList(),
)

@Serializable
data class ContextSpecDto(
    val db: String,
    val type: String,
    val searchText: String? = null,
    val filter: FilterParamsDto? = null,
)

@Serializable
data class FilterParamsDto(
    val keyword: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val category: String? = null,
    val isShared: Boolean? = null,
)

@Serializable
data class ChatSessionDto(
    val type: String,
    val text: String,
    val command: String? = null,
    val params: String? = null,
)


// For prompt-injection backends
@Serializable
data class PromptInjectionResponseDto(
    @SerialName("tool_call") val toolCall: PromptInjectionToolCallDto? = null,
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class PromptInjectionToolCallDto(
    val name: String,
    val arguments: JsonObject,
)
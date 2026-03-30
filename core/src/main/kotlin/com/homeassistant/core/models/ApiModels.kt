package com.homeassistant.core.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val platform: String,
    val conversationId: String,
    val userId: String,
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class ChatResponse(
    val type: String,
    val text: String,
    val sessionReset: Boolean = false,
)

data class ConversationMessage(
    val role: String,
    val content: String,
)

data class IntentAnalysis(
    val intent: String,
    val contexts: List<com.homeassistant.core.models.ContextSpec>,
)

data class ContextSpec(
    val db: String,
    val type: String,
    val searchText: String? = null,
    val filter: com.homeassistant.core.models.FilterParams? = null,
)

data class FilterParams(
    val keyword: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val category: String? = null,
    val isShared: Boolean? = null,
)

data class ContextResult(
    val db: String,
    val type: String,
    val rows: List<Map<String, Any?>>,
)

data class NlpChatResponse(
    val type: String,
    val text: String,
    val command: String? = null,
    val params: String? = null,
)

data class CommandResult(
    val text: String? = null,
)

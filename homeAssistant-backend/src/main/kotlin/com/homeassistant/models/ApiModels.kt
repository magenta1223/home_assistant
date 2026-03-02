package com.homeassistant.models

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
    val type: String,      // "question" | "result" | "unknown" | "error"
    val text: String,
    val sessionReset: Boolean = false,
)

// Internal NLP types (not serialized to client)
data class ConversationMessage(
    val role: String,   // "user" | "assistant"
    val content: String,
)

data class IntentAnalysis(
    val intent: String,
    val contexts: List<ContextSpec>,
)

data class ContextSpec(
    val db: String,
    val type: String,          // "recent" | "similar" | "query"
    val searchText: String? = null,
    val filter: FilterParams? = null,
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

// Claude's JSON response for the chat session
data class NlpChatResponse(
    val type: String,       // "question" | "result" | "unknown"
    val text: String,
    val command: String? = null,
    val params: String? = null,
)

// Command execution result
data class CommandResult(
    val text: String? = null,
)

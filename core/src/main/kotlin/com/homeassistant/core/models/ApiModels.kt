package com.homeassistant.core.models

import com.homeassistant.core.nlp.MessageRole
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

data class Message(
    val role: MessageRole,
    val content: String,
) {

    companion object {
        fun buildUserMessage(content: String): Message {
            return Message(
                role = MessageRole.USER,
                content = content
            )
        }

        fun buildSystemMessage(content: String): Message {
            return Message(
                role = MessageRole.ASSISTANT,
                content = content
            )
        }
    }
}

data class IntentAnalysis(
    val intent: String,
    val contexts: List<ContextSpec>,
)

data class ContextSpec(
    val db: String,
    val type: String,
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

data class NlpChatResponse(
    val type: String,
    val text: String,
    val command: String? = null,
    val params: String? = null,
)

data class CommandResult(
    val text: String? = null,
)

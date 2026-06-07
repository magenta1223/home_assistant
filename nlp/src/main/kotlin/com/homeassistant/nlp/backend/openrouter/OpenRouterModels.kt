package com.homeassistant.nlp.backend.openrouter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement


// ── Request ────────────────────────────────────────────────────────────

@Serializable
data class OpenRouterMessage(val role: String, val content: String)

@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    val max_tokens: Int? = null,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val response_format: OpenRouterResponseFormat? = null,
)

@Serializable
data class OpenRouterResponseFormat(
    val type: String = "json_schema",
    val json_schema: OpenRouterJsonSchemaResponseFormat,
)

@Serializable
data class OpenRouterJsonSchemaResponseFormat(
    val name: String,
    val strict: Boolean = true,
    val schema: JsonElement,
)

// ── Response ───────────────────────────────────────────────────────────

@Serializable
data class OpenRouterChoice(val message: OpenRouterMessage)

@Serializable
data class OpenRouterResponse(val choices: List<OpenRouterChoice>)

package com.homeassistant.nlp.backend.openrouter

import kotlinx.serialization.Serializable


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
)

// ── Response ───────────────────────────────────────────────────────────

@Serializable
data class OpenRouterChoice(val message: OpenRouterMessage)

@Serializable
data class OpenRouterResponse(val choices: List<OpenRouterChoice>)

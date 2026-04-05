package com.homeassistant.nlp

import kotlinx.serialization.Serializable

/** 생성자에서 주입하는 generation 설정. null = OpenRouter 기본값 사용. */
data class OpenRouterConfig(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
)

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

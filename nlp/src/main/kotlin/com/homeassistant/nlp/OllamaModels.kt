package com.homeassistant.nlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 생성자에서 주입하는 generation 설정. null = Ollama 기본값 사용. */
data class OllamaConfig(
    val think: Boolean = false,
    val temperature: Double? = null,
    val topK: Int? = null,
    val topP: Double? = null,
    val numPredict: Int? = null,
    val numCtx: Int? = null,
    val seed: Int? = null,
    val repeatPenalty: Double? = null,
)

// ── Request ────────────────────────────────────────────────────────────

@Serializable
data class OllamaMessage(val role: String, val content: String)

@Serializable
data class OllamaOptions(
    val temperature: Double? = null,
    @SerialName("top_k")          val topK: Int? = null,
    @SerialName("top_p")          val topP: Double? = null,
    @SerialName("num_predict")    val numPredict: Int? = null,
    @SerialName("num_ctx")        val numCtx: Int? = null,
    val seed: Int? = null,
    @SerialName("repeat_penalty") val repeatPenalty: Double? = null,
)

@Serializable
data class OllamaRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val think: Boolean = false,
    val options: OllamaOptions? = null,
)

// ── Response ───────────────────────────────────────────────────────────

@Serializable
data class OllamaResponseMessage(val role: String, val content: String)

@Serializable
data class OllamaResponse(
    val model: String? = null,
    val message: OllamaResponseMessage,
    val done: Boolean = false,
)

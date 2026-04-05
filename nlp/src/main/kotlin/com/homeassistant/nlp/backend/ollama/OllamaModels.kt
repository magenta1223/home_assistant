package com.homeassistant.nlp.backend.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


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

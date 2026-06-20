package com.homeassistant.nlp.backend.ollama

import com.homeassistant.core.nlp.MessageRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


// ── Request ────────────────────────────────────────────────────────────
/**
 * Message object in an Ollama chat request.
 *
 * @property role Message role sent to Ollama.
 * @property content Message text sent for that role.
 */
@Serializable
data class OllamaMessage(val role: MessageRole, val content: String)

/**
 * Optional Ollama generation options.
 *
 * @property temperature Optional sampling temperature.
 * @property topK Optional top-k sampling limit.
 * @property topP Optional nucleus sampling value.
 * @property numPredict Optional prediction token limit.
 * @property numCtx Optional context window size.
 * @property seed Optional deterministic sampling seed.
 * @property repeatPenalty Optional penalty for repeated tokens.
 */
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

/**
 * Ollama chat completion request body.
 *
 * @property model Ollama model name to call.
 * @property messages Ordered chat messages for the request.
 * @property stream Whether streaming responses are requested.
 * @property think Whether Ollama thinking mode is requested.
 * @property options Optional generation controls.
 */
@Serializable
data class OllamaRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val think: Boolean = false,
    val options: OllamaOptions? = null,
)

// ── Response ───────────────────────────────────────────────────────────

/**
 * Message object returned inside an Ollama chat response.
 *
 * @property role Role reported by Ollama for the response message.
 * @property content Response text content.
 */
@Serializable
data class OllamaResponseMessage(val role: String, val content: String)

/**
 * Ollama chat completion response body.
 *
 * @property model Optional model name reported by Ollama.
 * @property message Assistant message returned by Ollama.
 * @property done Whether Ollama reports the response as complete.
 */
@Serializable
data class OllamaResponse(
    val model: String? = null,
    val message: OllamaResponseMessage,
    val done: Boolean = false,
)

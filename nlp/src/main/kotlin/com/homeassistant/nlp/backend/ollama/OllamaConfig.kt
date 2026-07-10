package com.homeassistant.nlp.backend.ollama

import com.homeassistant.core.constants.AppConfig

/**
 * Runtime options used when creating Ollama chat requests.
 *
 * @property maxTokens Default maximum completion token budget.
 * @property temperature Optional sampling temperature.
 * @property think Whether Ollama thinking mode should be requested.
 * @property topK Optional top-k sampling limit.
 * @property topP Optional nucleus sampling value.
 * @property numPredict Optional Ollama prediction token limit.
 * @property numCtx Optional Ollama context window size.
 * @property seed Optional deterministic sampling seed.
 * @property repeatPenalty Optional penalty for repeated tokens.
 */
data class OllamaConfig(
    val maxTokens: Int = AppConfig.DEFAULT_LLM_MAX_TOKENS,
    val temperature: Double? = null,
    val think: Boolean = false,
    val topK: Int? = null,
    val topP: Double? = null,
    val numPredict: Int? = null,
    val numCtx: Int? = null,
    val seed: Int? = null,
    val repeatPenalty: Double? = null,
) {
    internal fun effectiveNumPredict(): Int? =
        numPredict ?: maxTokens.takeIf { it > 0 }
}

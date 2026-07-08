package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement


// ── Request ────────────────────────────────────────────────────────────

/**
 * Message object in an OpenRouter chat completion request or response.
 *
 * @property role Message role name.
 * @property content Message text content.
 */
@Serializable
data class OpenRouterMessage(val role: String, val content: String)

/**
 * OpenRouter chat completion request body.
 *
 * @property model OpenRouter model identifier to call.
 * @property messages Ordered chat messages for the request.
 * @property max_tokens Optional maximum completion token budget.
 * @property temperature Optional sampling temperature.
 * @property top_p Optional nucleus sampling value.
 * @property response_format Optional structured output format request.
 */
@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    val max_tokens: Int? = null,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val response_format: OpenRouterResponseFormat? = null,
)

/**
 * OpenRouter structured response format wrapper.
 *
 * @property type Response format type requested from OpenRouter.
 * @property json_schema JSON schema configuration for structured output.
 */
@Serializable
data class OpenRouterResponseFormat(
    val type: String = "json_schema",
    val json_schema: OpenRouterJsonSchemaResponseFormat,
)

/**
 * JSON schema configuration for OpenRouter structured output.
 *
 * @property name Schema name sent to OpenRouter.
 * @property strict Whether OpenRouter should enforce strict schema adherence.
 * @property schema JSON schema object for the expected response.
 */
@Serializable
data class OpenRouterJsonSchemaResponseFormat(
    val name: String,
    val strict: Boolean = true,
    val schema: JsonElement,
)

// ── Response ───────────────────────────────────────────────────────────

/**
 * One completion choice returned by OpenRouter.
 *
 * @property message Message payload for the choice.
 */
@Serializable
data class OpenRouterChoice(val message: OpenRouterMessage)

/**
 * OpenRouter chat completion response body.
 *
 * @property choices Completion choices returned by OpenRouter.
 * @property usage Token usage reported by OpenRouter when available.
 */
@Serializable
data class OpenRouterResponse(
    val choices: List<OpenRouterChoice>,
    val usage: OpenRouterUsage? = null,
)

/**
 * Token usage reported by OpenRouter.
 *
 * @property prompt_tokens Tokens billed for the request prompt.
 * @property completion_tokens Tokens billed for the model completion.
 * @property total_tokens Total billed prompt and completion tokens.
 */
@Serializable
data class OpenRouterUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null,
)

interface OpenRouterRawResponseHolder {
    val lastResponseBody: String?
}

class OpenRouterApiException(
    val statusCode: Int,
    val responseBody: String,
) : RuntimeException("OpenRouter API error $statusCode: $responseBody")

object OpenRouterResponseParser {
    fun parse(statusCode: Int, body: String): OpenRouterResponse {
        if (statusCode !in 200..299) {
            throw OpenRouterApiException(statusCode, body)
        }
        return body.decodeFromString()
    }
}

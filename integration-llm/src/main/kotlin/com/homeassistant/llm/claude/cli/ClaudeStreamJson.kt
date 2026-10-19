package com.homeassistant.llm.claude.cli

import com.homeassistant.llm.completion.CompletionImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** The final `result` event of a stream-json run. */
internal data class ClaudeResult(
    val isError: Boolean,
    val text: String?,
    val structuredOutput: JsonElement?,
)

/** Encodes stream-json user input and decodes the stream-json output of the Claude Code CLI. */
internal object ClaudeStreamJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun userMessage(text: String, images: List<CompletionImage> = emptyList()): String = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
                images.forEach { image ->
                    addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", mediaType(image.fileName))
                            put("data", Base64.getEncoder().encodeToString(image.bytes))
                        }
                    }
                }
            }
        }
    }.toString() + "\n"

    /** Returns the last `result` event, or null when the CLI emitted none. */
    fun result(stdout: String): ClaudeResult? = stdout.lineSequence()
        .mapNotNull { line -> runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() }
        .lastOrNull { it.string("type") == "result" }
        ?.let { event ->
            ClaudeResult(
                isError = event["is_error"]?.jsonPrimitive?.booleanOrNull ?: true,
                text = event.string("result"),
                structuredOutput = event["structured_output"]?.takeUnless { it is JsonNull },
            )
        }

    private fun JsonObject.string(key: String): String? =
        (this[key]?.takeUnless { it is JsonNull })?.jsonPrimitive?.contentOrNull

    private fun mediaType(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
}

package com.homeassistant.nlp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(OpenRouterBackend::class.java)

class OpenRouterBackend(
    private val apiKey: String,
    private val model: String,
) : LlmBackend {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    override suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double?,
    ): String? {
        log.info("OpenRouter call model=$model maxTokens=$maxTokens")
        log.info("OpenRouter prompt system='${system.take(100)}' messages=${messages.size}")

        val msgArray = buildJsonArray {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            messages.forEach { (role, content) ->
                addJsonObject {
                    put("role", role)
                    put("content", content)
                }
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("messages", msgArray)
            put("max_tokens", maxTokens)
            if (temperature != null) put("temperature", temperature)
        }

        val start = System.currentTimeMillis()
        val response = httpClient.post("https://openrouter.ai/api/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(body.toString())
        }

        val result = try {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
        } catch (_: Exception) { null }
        log.info("OpenRouter response ${System.currentTimeMillis() - start}ms chars=${result?.length}")
        return result
    }
}

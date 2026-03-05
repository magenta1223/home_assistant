package com.homeassistant.nlp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

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

        val response = httpClient.post("https://openrouter.ai/api/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(body.toString())
        }

        return try {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
        } catch (_: Exception) { null }
    }
}

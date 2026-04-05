package com.homeassistant.nlp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(OllamaBackend::class.java)

class OllamaBackend(
    private val baseUrl: String,
    private val model: String,
) : LlmBackend {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = false
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    override suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double?,
    ): String? {
        log.info("Ollama call model=$model baseUrl=$baseUrl maxTokens=$maxTokens")
        log.info("Ollama prompt system='${system.take(100)}' messages=${messages.size}")

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
//            put("max_tokens", maxTokens)
            put("stream", false)
            if (temperature != null) put("temperature", temperature)
        }

        val start = System.currentTimeMillis()
        val response = httpClient.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        log.info("Response Status: ${response.status}")
        val responseText = response.bodyAsText()
        log.info("Response Body: $responseText")

        val result = try {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            json["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
        } catch (_: Exception) { null }
        log.info("Ollama response ${System.currentTimeMillis() - start}ms chars=${result?.length}")
        return result
    }
}

package com.homeassistant.nlp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(OllamaBackend::class.java)

class OllamaBackend(
    private val baseUrl: String,
    private val model: String,
    private val config: OllamaConfig = OllamaConfig(),
) : LlmBackend {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    override suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        config: LlmConfig,
    ): String? {
        log.info("Ollama call model=$model baseUrl=$baseUrl maxTokens=${config.maxTokens}")
        log.info("Ollama prompt system='${system.take(100)}' messages=${messages.size}")

        val request = OllamaRequest(
            model = model,
            messages = buildList {
                add(OllamaMessage("system", system))
                messages.forEach { (role, content) -> add(OllamaMessage(role, content)) }
            },
            stream = false,
            think = this.config.think,
            options = OllamaOptions(
                temperature   = config.temperature ?: this.config.temperature,
                topK          = this.config.topK,
                topP          = this.config.topP,
                numPredict    = config.maxTokens.takeIf { it > 0 } ?: this.config.numPredict,
                numCtx        = this.config.numCtx,
                seed          = this.config.seed,
                repeatPenalty = this.config.repeatPenalty,
            ),
        )

        val start = System.currentTimeMillis()
        val response = httpClient.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        log.info("Response Status: ${response.status}")
        val responseText = response.bodyAsText()
        log.info("Response Body: $responseText")

        val result = try {
            json.decodeFromString<OllamaResponse>(responseText).message.content
        } catch (_: Exception) { null }

        log.info("Ollama response ${System.currentTimeMillis() - start}ms chars=${result?.length}")
        return result
    }
}

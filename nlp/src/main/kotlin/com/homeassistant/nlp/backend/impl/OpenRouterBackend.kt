package com.homeassistant.nlp.backend.impl

import com.homeassistant.nlp.backend.dto.OpenRouterConfig
import com.homeassistant.nlp.backend.dto.OpenRouterMessage
import com.homeassistant.nlp.backend.dto.OpenRouterRequest
import com.homeassistant.nlp.backend.dto.OpenRouterResponse
import com.homeassistant.nlp.backend.interfaces.LlmBackend
import com.homeassistant.nlp.backend.interfaces.LlmConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(OpenRouterBackend::class.java)

class OpenRouterBackend(
    private val apiKey: String,
    private val model: String,
    private val config: OpenRouterConfig = OpenRouterConfig(),
) : LlmBackend {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
    }

    override suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        config: LlmConfig,
    ): String? {
        log.info("OpenRouter call model=$model maxTokens=${config.maxTokens}")
        log.info("OpenRouter prompt system='${system.take(100)}' messages=${messages.size}")

        val request = OpenRouterRequest(
            model = model,
            messages = buildList {
                add(OpenRouterMessage("system", system))
                messages.forEach { (role, content) -> add(OpenRouterMessage(role, content)) }
            },
            max_tokens = config.maxTokens.takeIf { it > 0 } ?: this.config.maxTokens,
            temperature = config.temperature ?: this.config.temperature,
            top_p = this.config.topP,
        )

        val start = System.currentTimeMillis()
        val response = httpClient.post("https://openrouter.ai/api/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(request)
        }

        val result = try {
            json.decodeFromString<OpenRouterResponse>(response.bodyAsText())
                .choices.firstOrNull()?.message?.content
        } catch (_: Exception) { null }

        log.info("OpenRouter response ${System.currentTimeMillis() - start}ms chars=${result?.length}")
        return result
    }
}

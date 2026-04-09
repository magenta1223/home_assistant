package com.homeassistant.nlp.backend.ollama

import com.homeassistant.core.models.Message
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmRawResponse
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.SystemPrompt
import com.homeassistant.core.tools.Tool
import com.homeassistant.nlp.backend.utils.withTools
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
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    override suspend fun complete(system: SystemPrompt, messages: List<Message>, tools: List<Tool>): LlmResponse {
        log.info("Ollama call model=$model baseUrl=$baseUrl maxTokens=${config.maxTokens}")
        log.info("Ollama prompt system='${system.value.take(100)}' messages=${messages.size}")

        val request = OllamaRequest(
            model = model,
            messages = buildList {
                add(OllamaMessage("system", system.withTools(tools).value))
                messages.forEach { add(OllamaMessage(it.role.value, it.content)) }
            },
            stream = false,
            think = config.think,
            options = OllamaOptions(
                temperature = config.temperature,
                topK = config.topK,
                topP = config.topP,
                numPredict = config.maxTokens.takeIf { it > 0 },
                numCtx = config.numCtx,
                seed = config.seed,
                repeatPenalty = config.repeatPenalty,
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
        return result?.let {
            LlmResponse.Text(LlmRawResponse(it))
        } ?: run {
            LlmResponse.Text(LlmRawResponse("Response is null"))
        }
    }
}

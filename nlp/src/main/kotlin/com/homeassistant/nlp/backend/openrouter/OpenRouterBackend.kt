package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.tools.Tool
import com.homeassistant.core.utils.JsonSerializer
import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import com.homeassistant.core.utils.JsonSerializer.parseToJsonElement
import com.homeassistant.nlp.backend.utils.parseToolCallOrText
import com.homeassistant.nlp.backend.utils.withTools
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(OpenRouterBackend::class.java)

class OpenRouterBackend(
    private val apiKey: String,
    private val model: String,
    private val config: OpenRouterConfig = OpenRouterConfig(),
) : LlmBackend {


    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(JsonSerializer.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        log.info("OpenRouter call model=$model maxTokens=${config.maxTokens}")
        log.info("OpenRouter prompt system='${system.take(100)}' messages=${messages.size}")

        val request = OpenRouterRequest(
            model = model,
            messages = buildList {
                add(OpenRouterMessage("system", system.withTools(tools)))
                messages.forEach { add(OpenRouterMessage(it.role.value, it.content)) }
            },
            max_tokens = config.maxTokens.takeIf { it > 0 } ?: 512,
            temperature = config.temperature,
            top_p = config.topP,
            response_format = OpenRouterResponseFormat(
                json_schema = OpenRouterJsonSchemaResponseFormat(
                    name = "topic_analysis_output",
                    schema = outputSchema.parseToJsonElement(),
                ),
            ),
        )

        val start = System.currentTimeMillis()
        val response = httpClient.post("https://openrouter.ai/api/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(request)
        }

        val text = response
            .bodyAsText()
            .decodeFromString<OpenRouterResponse>()
            .choices
            .firstOrNull()
            ?.message
            ?.content
            ?: error("OpenRouter response had no content")
        log.info("OpenRouter response ${System.currentTimeMillis() - start}ms chars=${text.length}")
        return parseToolCallOrText(text)
    }
}

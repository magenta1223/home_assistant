package com.homeassistant.nlp.backend.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.homeassistant.core.models.Message
import com.homeassistant.core.nlp.MessageRole
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmRawResponse
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.SystemPrompt
import com.homeassistant.core.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull
import com.anthropic.models.messages.Tool as AnthropicTool

private val log = LoggerFactory.getLogger(AnthropicBackend::class.java)

class AnthropicBackend(
    apiKey: String,
    private val config: AnthropicConfig = AnthropicConfig(),
) : LlmBackend {

    private val json = Json {
        prettyPrint = true
        isLenient = true
    }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override suspend fun complete(system: SystemPrompt, messages: List<Message>, tools: List<Tool>): LlmResponse {
        return withContext(Dispatchers.IO) {
            log.info("Anthropic call model=${config.model} maxTokens=${config.maxTokens}")
            log.info("Anthropic prompt system='${system.value.take(100)}' messages=${messages.size}")

            val params = MessageCreateParams.builder()
                .model(config.model)
                .maxTokens(config.maxTokens.toLong())
                .system(system.value)
                .apply {
                    tools.forEach {
                        addTool(it.toClaudeCompatible())
                    }
                    messages.forEach { msg ->
                        when (msg.role.value) {
                            MessageRole.USER.value -> addUserMessage(msg.content)
                            MessageRole.ASSISTANT.value -> addAssistantMessage(msg.content)
                        }
                    }

                    config.temperature?.let { temperature(it) }
                }
                .build()

            val start = System.currentTimeMillis()
            val response = client.messages().create(params)
            val result = response.content().firstOrNull()?.text()?.getOrNull()?.text()
            log.info("Anthropic response ${System.currentTimeMillis() - start}ms chars=${result?.length}")

            result?.let {
                // TODO: Check response is tool
                LlmResponse.Text(LlmRawResponse("Response Null"))
            } ?: run {
                LlmResponse.Text(LlmRawResponse("Response Null"))
            }
        }
    }

    private fun Tool.toClaudeCompatible(): AnthropicTool {
        return AnthropicTool.builder()
            .name(name.value)
            .description(description.value)
            .inputSchema(JsonValue.from(schema))
            .build()
    }
}

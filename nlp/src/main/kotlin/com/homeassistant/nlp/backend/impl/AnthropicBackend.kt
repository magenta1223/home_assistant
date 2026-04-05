package com.homeassistant.nlp.backend.impl

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.homeassistant.core.nlp.MessageRole
import com.homeassistant.nlp.backend.dto.AnthropicConfig
import com.homeassistant.nlp.backend.interfaces.LlmBackend
import com.homeassistant.nlp.backend.interfaces.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull

private val log = LoggerFactory.getLogger(AnthropicBackend::class.java)

class AnthropicBackend(
    apiKey: String,
    private val config: AnthropicConfig = AnthropicConfig(),
) : LlmBackend {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        config: LlmConfig,
    ): String? = withContext(Dispatchers.IO) {
        log.info("Anthropic call model=${this@AnthropicBackend.config.model} maxTokens=${config.maxTokens}")
        log.info("Anthropic prompt system='${system.take(100)}' messages=${messages.size}")

        val params = MessageCreateParams.builder()
            .model(this@AnthropicBackend.config.model)
            .maxTokens(config.maxTokens.toLong())
            .system(system)
            .apply {
                messages.forEach { (role, content) ->
                    when (role) {
                        MessageRole.USER.value      -> addUserMessage(content)
                        MessageRole.ASSISTANT.value -> addAssistantMessage(content)
                    }
                }
                val t = config.temperature ?: this@AnthropicBackend.config.temperature
                if (t != null) temperature(t)
            }
            .build()

        val start = System.currentTimeMillis()
        val response = client.messages().create(params)
        val result = response.content().firstOrNull()?.text()?.getOrNull()?.text()
        log.info("Anthropic response ${System.currentTimeMillis() - start}ms chars=${result?.length}")
        result
    }
}

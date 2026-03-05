package com.homeassistant.nlp

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.homeassistant.constants.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.jvm.optionals.getOrNull

class AnthropicBackend(apiKey: String) : LlmBackend {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    private val model = Model.CLAUDE_3_5_HAIKU_LATEST

    override suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double?,
    ): String? = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens.toLong())
            .system(system)
            .apply {
                messages.forEach { (role, content) ->
                    when (role) {
                        MessageRole.USER.value      -> this.addUserMessage(content)
                        MessageRole.ASSISTANT.value -> this.addAssistantMessage(content)
                    }
                }
                if (temperature != null) this.temperature(temperature)
            }
            .build()

        val response = client.messages().create(params)
        response.content().firstOrNull()?.text()?.getOrNull()?.text()
    }
}

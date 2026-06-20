package com.homeassistant.nlp.backend.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.nlp.MessageRole
import com.homeassistant.core.tools.Tool
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.nlp.backend.utils.parseToolCallOrText
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

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        return withContext(Dispatchers.IO) {
            log.info("Anthropic call model=${config.model} maxTokens=${config.maxTokens}")
            log.info("Anthropic prompt system='${system.take(100)}' messages=${messages.size}")

            val params = MessageCreateParams.builder()
                .model(config.model)
                .maxTokens(config.maxTokens.toLong())
                .system(system)
                .apply {
                    tools.forEach {
                        addTool(it.toClaudeCompatible())
                    }
                    messages.forEach { msg ->
                        when (msg.role) {
                            MessageRole.USER        -> addUserMessage(msg.content)
                            MessageRole.ASSISTANT   -> addAssistantMessage(msg.content)
                            MessageRole.TOOL_RESULT -> Unit  // TODO: implement tool result handling
                            MessageRole.SYSTEM -> Unit
                        }
                    }

                    config.temperature?.let { temperature(it) }
                }
                .build()

            val start = System.currentTimeMillis()
            val response = client.messages().create(params)
            parseNativeToolCall(response.content())?.let { return@withContext it }
            val result = response.content().firstOrNull()?.text()?.getOrNull()?.text()
            log.info("Anthropic response ${System.currentTimeMillis() - start}ms chars=${result?.length}")

            result?.let { parseToolCallOrText(it) }
                ?: error("Anthropic response content was null")
        }
    }

    private fun parseNativeToolCall(contentBlocks: List<Any>): LlmResponse.ToolCall? {
        contentBlocks.forEach { block ->
            val toolUse = invokeOptional(block, "toolUse") ?: return@forEach
            val name = invokeValue(toolUse, "name")?.toString() ?: return@forEach
            val input = invokeValue(toolUse, "input")?.toString() ?: "{}"
            return LlmResponse.ToolCall(ToolCallSpec(name, input))
        }
        return null
    }

    private fun invokeOptional(target: Any, methodName: String): Any? {
        val optional = invokeValue(target, methodName) ?: return null
        val getOrNull = optional::class.java.methods.firstOrNull { it.name == "getOrNull" && it.parameterCount == 0 }
        if (getOrNull != null) return getOrNull.invoke(optional)
        val isPresent = optional::class.java.methods.firstOrNull { it.name == "isPresent" && it.parameterCount == 0 }
        val get = optional::class.java.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }
        return if (isPresent?.invoke(optional) == true) get?.invoke(optional) else null
    }

    private fun invokeValue(target: Any, methodName: String): Any? =
        target::class.java.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.invoke(target)

    private fun Tool.toClaudeCompatible(): AnthropicTool {
        return AnthropicTool.builder()
            .name(name)
            .description(description)
            .inputSchema(JsonValue.from(schema))
            .build()
    }
}

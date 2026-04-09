package com.homeassistant.nlp.models

import com.homeassistant.core.nlp.AiClient
import com.homeassistant.core.models.*
import com.homeassistant.core.nlp.ChatResponseType
import com.homeassistant.core.nlp.PromptConfig
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.tools.Tool
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AiClientImpl(
    private val backend: LlmBackend,
    private val promptConfig: PromptConfig,
    private val tools: List<Tool> = emptyList(),
) : AiClient {

    private val log = LoggerFactory.getLogger(AiClientImpl::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun analyzeIntent(messages: List<Message>): IntentAnalysis {
        val response = backend.complete(promptConfig.intentSystemPrompt, messages)
        return try {

            // TODO
            val dto = json.decodeFromString<IntentAnalysisDto>("")
            log.info("analyzeIntent intent=${dto.intent} contexts=${dto.contexts.size}")
            IntentAnalysis(
                intent = dto.intent,
                contexts = dto.contexts.map { contextSpecDto ->
                    ContextSpec(
                        db = contextSpecDto.db,
                        type = contextSpecDto.type,
                        searchText = contextSpecDto.searchText,
                        filter = contextSpecDto.filter?.let {
                            FilterParams(
                                keyword = it.keyword,
                                dateFrom = it.dateFrom,
                                dateTo = it.dateTo,
                                category = it.category,
                                isShared = it.isShared,
                            )
                        },
                    )
                },
            )
        } catch (_: Exception) {
            log.warn("analyzeIntent failed to parse response")
            IntentAnalysis(ChatResponseType.UNKNOWN.value, emptyList())
        }
    }

    override suspend fun chatSession(
        messages: List<Message>,
        contextResults: List<ContextResult>,
    ): NlpChatResponse {
        return when (val response = backend.complete(promptConfig.chatbotSystemPrompt, messages, tools)) {
            is LlmResponse.Text -> {
                val dto = json.decodeFromString<ChatSessionDto>(response.content.value)
                NlpChatResponse(type = ChatResponseType.valueOf(dto.type.uppercase()), text = dto.text)
            }

            is LlmResponse.ToolCall -> NlpChatResponse(
                type = ChatResponseType.TOOL_CALL,
                text = "",
                toolCall = response.spec,
            )
        }
    }
}

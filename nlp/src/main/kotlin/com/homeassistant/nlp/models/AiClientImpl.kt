package com.homeassistant.nlp.models

import com.homeassistant.core.nlp.AiClient
import com.homeassistant.core.nlp.CoreMessages
import com.homeassistant.core.models.*
import com.homeassistant.core.nlp.ChatResponseType
import com.homeassistant.core.nlp.PromptConfig
import com.homeassistant.core.nlp.LlmBackend
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AiClientImpl(
    private val backend: LlmBackend,
    private val promptConfig: PromptConfig,
) : AiClient {

    private val log = LoggerFactory.getLogger(AiClientImpl::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun analyzeIntent(messages: List<Message>): IntentAnalysis {
        val raw = backend.complete(promptConfig.intentSystemPrompt, messages)
            ?: return IntentAnalysis(ChatResponseType.UNKNOWN.value, emptyList())
        return try {
            val dto = json.decodeFromString<IntentAnalysisDto>(raw.value)
            log.info("analyzeIntent intent=${dto.intent} contexts=${dto.contexts.size}")
            IntentAnalysis(
                intent = dto.intent,
                contexts = dto.contexts.map { c ->
                    ContextSpec(
                        db = c.db,
                        type = c.type,
                        searchText = c.searchText,
                        filter = c.filter?.let { f ->
                            FilterParams(
                                keyword = f.keyword,
                                dateFrom = f.dateFrom,
                                dateTo = f.dateTo,
                                category = f.category,
                                isShared = f.isShared,
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
        val raw = backend.complete(promptConfig.chatbotSystemPrompt, messages)
            ?: return NlpChatResponse(ChatResponseType.UNKNOWN.value, CoreMessages.NLP_FALLBACK).also {
                log.error("chatSession response is null")
            }
        return try {
            val dto = json.decodeFromString<ChatSessionDto>(raw.value)
            log.info("chatSession type=${dto.type} command=${dto.command}")
            NlpChatResponse(
                type = dto.type,
                text = dto.text,
                command = dto.command,
                params = dto.params,
            )
        } catch (_: Exception) {
            log.warn("chatSession failed to parse response")
            NlpChatResponse(ChatResponseType.UNKNOWN.value, CoreMessages.NLP_FALLBACK)
        }
    }
}

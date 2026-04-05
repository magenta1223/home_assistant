package com.homeassistant.nlp.models

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.nlp.AiClientBase
import com.homeassistant.core.nlp.CoreMessages
import com.homeassistant.core.nlp.MessageRole
import com.homeassistant.core.models.*
import com.homeassistant.core.nlp.ChatResponseType
import com.homeassistant.core.nlp.PromptConfig
import com.homeassistant.nlp.backend.interfaces.LlmBackend
import com.homeassistant.nlp.backend.interfaces.LlmConfig
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory


class AiClient(
    private val backend: LlmBackend,
    private val prompts: PromptConfig,
) : AiClientBase {

    private val log = LoggerFactory.getLogger(AiClient::class.java)

    // ── Intent analyzer ──────────────────────────────────────────────────────
    override suspend fun analyzeIntent(
        history: List<ConversationMessage>,
        userText: String,
    ): IntentAnalysis {
        val messages = buildMessages(history, userText)
        val responseText = callBackend(
            system = prompts.intentSystemPrompt,
            messages = messages,
            maxTokens = AppConfig.MAX_TOKENS_INTENT,
            temperature = 0.0,
        ) ?: return IntentAnalysis(ChatResponseType.UNKNOWN.value, emptyList())

        return try {
            val obj = Json.parseToJsonElement(responseText.trim()).jsonObject
            val intent = obj["intent"]?.jsonPrimitive?.content ?: ChatResponseType.UNKNOWN.value
            val contexts = obj["contexts"]?.jsonArray?.mapNotNull { elem ->
                val o = elem.jsonObject
                val db = o["db"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val type = o["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val searchText = o["searchText"]?.jsonPrimitive?.content
                val filter = o["filter"]?.jsonObject?.let { f ->
                    FilterParams(
                        keyword = f["keyword"]?.jsonPrimitive?.content,
                        dateFrom = f["dateFrom"]?.jsonPrimitive?.content,
                        dateTo = f["dateTo"]?.jsonPrimitive?.content,
                        category = f["category"]?.jsonPrimitive?.content,
                        isShared = f["isShared"]?.jsonPrimitive?.booleanOrNull,
                    )
                }
                ContextSpec(db = db, type = type, searchText = searchText, filter = filter)
            } ?: emptyList()
            log.info("analyzeIntent intent=$intent contexts=${contexts.size}")
            IntentAnalysis(intent, contexts)
        } catch (_: Exception) {
            log.warn("analyzeIntent failed to parse response")
            IntentAnalysis(ChatResponseType.UNKNOWN.value, emptyList())
        }
    }

    // ── Chat session ─────────────────────────────────────────────────────────

    override suspend fun chatSession(
        history: List<ConversationMessage>,
        userMessage: String,
        context: List<ContextResult>,
    ): NlpChatResponse {
        val contextBlock = formatContext(context)
        val fullUserMessage = if (contextBlock.isNotEmpty())
            "[context]\n$contextBlock\n[/context]\n\n$userMessage"
        else
            userMessage

        val messages = buildMessages(history, fullUserMessage)
        val responseText = callBackend(
            system = prompts.chatbotSystemPrompt,
            messages = messages,
            maxTokens = AppConfig.MAX_TOKENS_CHAT,
        ) ?: return NlpChatResponse(ChatResponseType.UNKNOWN.value, CoreMessages.NLP_FALLBACK).also {
            log.error("response is null")
        }

        return try {
            val obj = Json.parseToJsonElement(responseText.trim()).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: ChatResponseType.UNKNOWN.value
            val command = obj["command"]?.jsonPrimitive?.content
            log.info("chatSession prompt='${userMessage.take(200)}' → type=$type command=$command")
            NlpChatResponse(
                type = type,
                text = obj["text"]?.jsonPrimitive?.content ?: CoreMessages.NLP_FALLBACK,
                command = command,
                params = obj["params"]?.jsonPrimitive?.content,
            )
        } catch (_: Exception) {
            log.warn("chatSession failed to parse response for prompt='${userMessage.take(200)}'")
            NlpChatResponse(ChatResponseType.UNKNOWN.value, CoreMessages.NLP_FALLBACK)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun formatContext(results: List<ContextResult>): String =
        results.filter { it.rows.isNotEmpty() }
            .joinToString("\n\n") { r ->
                val label = "${r.db} (${r.type})"
                val lines = r.rows.take(AppConfig.CONTEXT_ROWS_SHOWN).joinToString("\n") { row ->
                    val values = row.entries
                        .filter { it.key !in setOf("id", "user_id", "is_shared") }
                        .joinToString(", ") { "${it.key}: ${it.value}" }
                    "- $values"
                }
                "$label:\n$lines"
            }

    private fun buildMessages(
        history: List<ConversationMessage>,
        userText: String,
    ): List<Pair<String, String>> =
        history.map { Pair(it.role, it.content) } + Pair(MessageRole.USER.value, userText)

    private suspend fun callBackend(
        system: String,
        userMessage: String? = null,
        messages: List<Pair<String, String>>? = null,
        maxTokens: Int = AppConfig.MAX_TOKENS_CHAT,
        temperature: Double? = null,
    ): String? {
        val msgList = when {
            messages != null -> messages
            userMessage != null -> listOf(Pair(MessageRole.USER.value, userMessage))
            else -> return null
        }
        return backend.complete(system, msgList, LlmConfig(maxTokens, temperature))
    }
}

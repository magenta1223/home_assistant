package com.homeassistant.core.nlp

import com.homeassistant.core.models.ContextResult
import com.homeassistant.core.models.ConversationMessage
import com.homeassistant.core.models.IntentAnalysis
import com.homeassistant.core.models.NlpChatResponse

interface AiClient {
    suspend fun analyzeIntent(
        history: List<ConversationMessage>,
        userText: String
    ): IntentAnalysis

    suspend fun chatSession(
        history: List<ConversationMessage>,
        userMessage: String,
        context: List<ContextResult> = emptyList()
    ): NlpChatResponse

    suspend fun getGenerationConfig(): LlmConfig

}

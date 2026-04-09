package com.homeassistant.core.nlp

import com.homeassistant.core.models.ContextResult
import com.homeassistant.core.models.Message
import com.homeassistant.core.models.IntentAnalysis
import com.homeassistant.core.models.NlpChatResponse

interface AiClient {
    suspend fun analyzeIntent(messages: List<Message>): IntentAnalysis
    suspend fun chatSession(
        messages: List<Message>,
        contextResults: List<ContextResult> = emptyList(),
    ): NlpChatResponse
}

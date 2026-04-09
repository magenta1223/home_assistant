package com.homeassistant.core.nlp

import com.homeassistant.core.models.Message
import com.homeassistant.core.tools.Tool

interface LlmBackend {
    suspend fun complete(
        system: SystemPrompt,
        messages: List<Message>,
        tools: List<Tool> = emptyList(),
    ): LlmResponse
}
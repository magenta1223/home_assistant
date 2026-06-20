package com.homeassistant.core.nlp

import com.homeassistant.core.tools.Tool

interface LlmBackend {
    suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool> = emptyList(),
        outputSchema: String,
    ): LlmResponse
}

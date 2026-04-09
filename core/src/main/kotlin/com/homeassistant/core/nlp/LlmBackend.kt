package com.homeassistant.core.nlp

import com.homeassistant.core.models.Message

interface LlmBackend {
    suspend fun complete(system: SystemPrompt, messages: List<Message>): LlmRawResponse?
}

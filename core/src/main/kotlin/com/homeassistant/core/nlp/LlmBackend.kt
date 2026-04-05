package com.homeassistant.core.nlp

interface LlmBackend {
    suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        config: LlmConfig
    ): String?
}
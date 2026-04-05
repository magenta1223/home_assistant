package com.homeassistant.nlp.backend.interfaces

interface LlmBackend {
    suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        config: LlmConfig
    ): String?
}
package com.homeassistant.nlp

interface LlmBackend {
    suspend fun complete(
        system: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double?,
    ): String?
}

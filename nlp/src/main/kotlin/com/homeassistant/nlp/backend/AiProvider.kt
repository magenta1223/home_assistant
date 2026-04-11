package com.homeassistant.nlp.backend

enum class AiProvider {
    OLLAMA, OPENROUTER, ANTHROPIC;

    companion object {
        fun from(value: String): AiProvider =
            entries.find { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown AI provider: $value")
    }
}

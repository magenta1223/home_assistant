package com.homeassistant.core.nlp

interface PromptConfig {
    val intentSystemPrompt: String
    val chatbotSystemPrompt: String
}

package com.homeassistant.core.nlp

interface PromptConfig {
    val intentSystemPrompt: SystemPrompt
    val chatbotSystemPrompt: SystemPrompt
}

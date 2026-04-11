package com.homeassistant.core.nlp

enum class MessageRole(val value: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL_RESULT("tool")
    ;
}

package com.homeassistant.core.nlp

enum class ChatResponseType(val value: String) {
    QUESTION("question"),
    RESULT("result"),
    UNKNOWN("unknown"),
    ERROR("error");
}

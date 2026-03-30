package com.homeassistant.core.nlcore

enum class ChatResponseType(val value: String) {
    QUESTION("question"),
    RESULT("result"),
    UNKNOWN("unknown"),
    ERROR("error");
}

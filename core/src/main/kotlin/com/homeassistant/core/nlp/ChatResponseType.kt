package com.homeassistant.core.nlp

enum class ChatResponseType(val value: String) {
    QUESTION("question"),
    RESULT("result"),
    TOOL_CALL("tool_call"),   // new
    UNKNOWN("unknown"),
    ERROR("error");
}

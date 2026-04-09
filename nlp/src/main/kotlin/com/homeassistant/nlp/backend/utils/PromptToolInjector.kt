package com.homeassistant.nlp.backend.utils

import com.homeassistant.core.nlp.SystemPrompt
import com.homeassistant.core.tools.Tool
import kotlinx.serialization.json.Json

fun SystemPrompt.withTools(tools: List<Tool>): SystemPrompt {
    if (tools.isEmpty()) return this
    val toolsJson = Json.encodeToString(tools)
    return SystemPrompt(
        "$value\n\n사용 가능한 도구 목록 (JSON Schema):\n$toolsJson\n\n" +
                "도구를 호출해야 할 경우 반드시 아래 형식으로만 응답하세요:\n" +
                "{\"tool_call\":{\"name\":\"도구이름\",\"arguments\":{...}}}"
    )
}
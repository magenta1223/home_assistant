package com.homeassistant.codex.conversation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexAppServerProtocolTest {
    @Test
    fun `serializes thread start params with protocol field names`() {
        val params = AppServerProtocol.ThreadStart(
            model = "test-model",
            cwd = "C:/work",
            config = ThreadConfig(modelReasoningEffort = "medium"),
        )
        val actual = CODEX_JSON.encodeToString(AppServerProtocol.ThreadStart.serializer(), params)

        assertEquals(
            Json.parseToJsonElement(
                """{
                  "model":"test-model",
                  "cwd":"C:/work",
                  "approvalPolicy":"never",
                  "sandbox":"read-only",
                  "serviceName":"home_second_brain",
                  "config":{
                    "web_search":"disabled",
                    "model_reasoning_effort":"medium"
                  }
                }""".trimIndent(),
            ),
            Json.parseToJsonElement(actual),
        )
    }

}

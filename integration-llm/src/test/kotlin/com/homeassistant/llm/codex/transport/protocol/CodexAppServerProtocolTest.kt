package com.homeassistant.llm.codex.transport.protocol

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
                  "ephemeral":false,
                  "config":{
                    "web_search":"disabled",
                    "model_reasoning_effort":"medium"
                  }
                }""".trimIndent(),
            ),
            Json.parseToJsonElement(actual),
        )
    }

    @Test
    fun `serializes text and local images with Codex input discriminators`() {
        val params = AppServerProtocol.TurnStart(
            threadId = "thread-1",
            input = listOf(TextUserInput("interpret"), LocalImageUserInput("C:/temp/input-1.png")),
            cwd = "C:/temp",
            model = "test-model",
            effort = "high",
            outputSchema = Json.parseToJsonElement("""{"type":"object"}"""),
        )

        val actual = CODEX_JSON.encodeToJsonElement(AppServerProtocol.TurnStart.serializer(), params)

        assertEquals(
            Json.parseToJsonElement("""{
              "threadId":"thread-1",
              "input":[{"type":"text","text":"interpret"},{"type":"localImage","path":"C:/temp/input-1.png"}],
              "cwd":"C:/temp",
              "approvalPolicy":"never",
              "sandboxPolicy":{"type":"readOnly"},
              "model":"test-model",
              "effort":"high",
              "outputSchema":{"type":"object"}
            }"""),
            actual,
        )
    }
}

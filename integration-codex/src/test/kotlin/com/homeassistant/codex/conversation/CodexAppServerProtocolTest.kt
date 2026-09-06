package com.homeassistant.codex.conversation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexAppServerProtocolTest {
    @Test
    fun `serializes thread start request with protocol field names`() {
        val params = ThreadStartParams(
            model = "test-model",
            cwd = "C:/work",
            approvalPolicy = ApprovalPolicy.NEVER,
            sandbox = SandboxMode.READ_ONLY,
            serviceName = "home_second_brain",
            config = ThreadConfig(modelReasoningEffort = "medium"),
        )
        val request = JsonRpcRequest(7, AppServerProtocol.threadStart.wireName, params)

        val actual = CODEX_JSON.encodeToString(
            JsonRpcRequest.serializer(AppServerProtocol.threadStart.paramsSerializer),
            request,
        )

        assertEquals(
            Json.parseToJsonElement(
                """{
                  "id":7,
                  "method":"thread/start",
                  "params":{
                    "model":"test-model",
                    "cwd":"C:/work",
                    "approvalPolicy":"never",
                    "sandbox":"read-only",
                    "serviceName":"home_second_brain",
                    "config":{
                      "web_search":"disabled",
                      "model_reasoning_effort":"medium"
                    }
                  }
                }""".trimIndent(),
            ),
            Json.parseToJsonElement(actual),
        )
    }

    @Test
    fun `serializes initialized as a notification without an id`() {
        val notification = JsonRpcNotification(
            AppServerProtocol.initialized.wireName,
            EmptyParams,
        )

        val actual = CODEX_JSON.encodeToString(
            JsonRpcNotification.serializer(AppServerProtocol.initialized.paramsSerializer),
            notification,
        )

        assertEquals(
            Json.parseToJsonElement("""{"method":"initialized","params":{}}"""),
            Json.parseToJsonElement(actual),
        )
    }
}

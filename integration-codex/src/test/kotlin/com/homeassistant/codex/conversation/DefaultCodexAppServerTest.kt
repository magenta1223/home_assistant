package com.homeassistant.codex.conversation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultCodexAppServerTest {
    @Test
    fun `sends initialized as a notification without an id`() {
        val transport = FakeAppServerTransport()
        val server = server(transport)
        try {
            val initialized = transport.sentMessages
                .map { CODEX_JSON.parseToJsonElement(it) as JsonObject }
                .single { it["method"]?.jsonPrimitive?.content == AppServerProtocol.Initialized.method }
            assertNull(initialized["id"])
        } finally {
            server.close()
        }
    }

    @Test
    fun `creates distinct threads without starting turns`() {
        val transport = FakeAppServerTransport()
        val server = server(transport)
        try {
            val firstThread = server.createThread().getOrThrow()
            val secondThread = server.createThread().getOrThrow()

            assertEquals(1, transport.startCount)
            assertNotEquals(firstThread, secondThread)
            assertEquals(2, transport.methods.count { it == "thread/start" })
            assertEquals(0, transport.methods.count { it == "turn/start" })

            assertTrue(server.executeTurn(firstThread, "first prompt").isSuccess)
            assertTrue(server.executeTurn(secondThread, "second prompt").isSuccess)

            assertEquals(2, transport.methods.count { it == "turn/start" })
            transport.turnParams.forEach { params ->
                val schema = params["outputSchema"] as JsonObject
                assertEquals("object", schema["type"]?.jsonPrimitive?.content)
                assertEquals("false", schema["additionalProperties"]?.jsonPrimitive?.content)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `continues a loaded thread without reloading it`() {
        val transport = FakeAppServerTransport()
        val server = server(transport)
        try {
            val threadId = server.createThread().getOrThrow()
            server.executeTurn(threadId, "first")

            val result = server.executeTurn(threadId, "follow up")

            assertEquals("structured answer", result.getOrThrow())
            assertEquals(0, transport.methods.count { it == "thread/resume" })
        } finally {
            server.close()
        }
    }

    @Test
    fun `unsubscribes an ended thread and reloads it on the next execution`() {
        val transport = FakeAppServerTransport()
        val server = server(transport)
        try {
            val threadId = server.createThread().getOrThrow()
            server.executeTurn(threadId, "first")

            server.releaseThread(threadId)
            server.executeTurn(threadId, "follow up")

            assertEquals(listOf(threadId.value), transport.unsubscribedThreads)
            assertEquals(1, transport.methods.count { it == "thread/resume" })
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects an answer that does not match the required structure`() {
        val transport = FakeAppServerTransport(answerPayload = "plain text")
        val server = server(transport)
        try {
            val threadId = server.createThread().getOrThrow()
            val result = server.executeTurn(threadId, "prompt")

            assertEquals("INVALID_STRUCTURED_ANSWER", result.exceptionOrNull()?.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects a malformed recognized notification instead of waiting for timeout`() {
        val transport = FakeAppServerTransport(omitCompletedAt = true)
        val server = server(transport)
        try {
            val threadId = server.createThread().getOrThrow()

            val result = server.executeTurn(threadId, "prompt")

            assertEquals("INVALID_APP_SERVER_MESSAGE", result.exceptionOrNull()?.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun `responds to a server request with a string id`() {
        val transport = FakeAppServerTransport()
        val server = server(transport)
        try {
            transport.sendServerRequest("server-request-1")

            val response = CODEX_JSON.parseToJsonElement(transport.sentMessages.last()) as JsonObject
            assertEquals("server-request-1", response["id"]?.jsonPrimitive?.content)
            assertEquals(
                -32601,
                response["error"]?.let { it as JsonObject }?.get("code")?.jsonPrimitive?.content?.toInt(),
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `structured answer rejects extra fields`() {
        assertNull(parseStructuredAnswer("""{"answer":"valid","extra":true}"""))
    }

    private fun server(transport: FakeAppServerTransport): CodexAppServer =
        requireNotNull(
            CodexAppServerFactory.create(
                config = CodexConversationConfig(
                executable = "unused",
                workDir = Files.createTempDirectory("codex-app-server-test-"),
                timeout = Duration.ofSeconds(5),
                ),
                transport = transport,
                availabilityProbe = { true },
            ),
        )

    private class FakeAppServerTransport(
        private val answerPayload: String = "{\"answer\":\"structured answer\"}",
        private val omitCompletedAt: Boolean = false,
    ) : AppServerTransport {
        override var isAlive: Boolean = false
            private set
        var startCount = 0
        val methods = mutableListOf<String>()
        val turnParams = mutableListOf<JsonObject>()
        val unsubscribedThreads = mutableListOf<String>()
        val sentMessages = mutableListOf<String>()
        private var onMessage: (String) -> Unit = {}
        private var onClosed: () -> Unit = {}
        private var nextThread = 1

        override fun start(onMessage: (String) -> Unit, onClosed: () -> Unit): Boolean {
            if (!isAlive) startCount++
            isAlive = true
            this.onMessage = onMessage
            this.onClosed = onClosed
            return true
        }

        override fun send(message: String) {
            sentMessages += message
            val request = CODEX_JSON.parseToJsonElement(message) as JsonObject
            val method = request["method"]?.jsonPrimitive?.content ?: return
            methods += method
            val id = request["id"] ?: return
            val params = request["params"] as? JsonObject ?: buildJsonObject {}
            when (method) {
                "initialize" -> respond(id, buildJsonObject {})
                "thread/start" -> {
                    val threadId = "00000000-0000-0000-0000-${nextThread++.toString().padStart(12, '0')}"
                    respond(id, buildJsonObject {
                        put("thread", buildJsonObject { put("id", threadId) })
                    })
                }
                "thread/resume" -> respond(id, buildJsonObject {
                    put("thread", buildJsonObject { put("id", params.string("threadId")) })
                })
                "turn/start" -> {
                    turnParams += params
                    val threadId = params.string("threadId")
                    val turnId = "turn-${turnParams.size}"
                    respond(id, buildJsonObject {
                        put("turn", buildJsonObject { put("id", turnId) })
                    })
                    notify("item/completed", buildJsonObject {
                        put("threadId", threadId)
                        put("turnId", turnId)
                        if (!omitCompletedAt) put("completedAtMs", 1L)
                        put("item", buildJsonObject {
                            put("id", "item-${turnParams.size}")
                            put("type", "agentMessage")
                            put("text", answerPayload)
                        })
                    })
                    notify("turn/completed", buildJsonObject {
                        put("threadId", threadId)
                        put("turn", buildJsonObject {
                            put("id", turnId)
                            put("status", "completed")
                            put("items", buildJsonArray {})
                            put("error", JsonNull)
                        })
                    })
                }
                "thread/unsubscribe" -> {
                    unsubscribedThreads += params.string("threadId")
                    respond(id, buildJsonObject { put("status", "unsubscribed") })
                }
                "turn/interrupt" -> respond(id, buildJsonObject {})
            }
        }

        fun sendServerRequest(id: String) {
            onMessage(
                buildJsonObject {
                    put("id", id)
                    put("method", "unsupported/test")
                    put("params", buildJsonObject {})
                }.toString(),
            )
        }

        override fun stop() {
            isAlive = false
        }

        override fun close() {
            isAlive = false
        }

        private fun respond(id: JsonElement, result: JsonObject) {
            onMessage(buildJsonObject {
                put("id", id)
                put("result", result)
            }.toString())
        }

        private fun notify(method: String, params: JsonObject) {
            onMessage(buildJsonObject {
                put("method", method)
                put("params", params)
            }.toString())
        }

        private fun JsonObject.string(key: String): String =
            this[key]?.jsonPrimitive?.content ?: error("missing $key")
    }
}

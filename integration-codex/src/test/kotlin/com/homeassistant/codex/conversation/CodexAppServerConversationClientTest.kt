package com.homeassistant.codex.conversation

import kotlinx.serialization.json.JsonElement
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
import kotlin.test.assertTrue

class CodexAppServerConversationClientTest {
    @Test
    fun `creates distinct threads without starting turns`() {
        val transport = FakeAppServerTransport()
        val client = client(transport)
        try {
            assertTrue(client.startServer())

            val firstThread = client.create().getOrThrow()
            val secondThread = client.create().getOrThrow()

            assertEquals(1, transport.startCount)
            assertNotEquals(firstThread, secondThread)
            assertEquals(2, transport.methods.count { it == "thread/start" })
            assertEquals(0, transport.methods.count { it == "turn/start" })

            assertTrue(client.execute(firstThread, "first prompt").isSuccess)
            assertTrue(client.execute(secondThread, "second prompt").isSuccess)

            assertEquals(2, transport.methods.count { it == "turn/start" })
            assertTrue(transport.turnParams.all { it["outputSchema"] is JsonObject })
        } finally {
            client.close()
        }
    }

    @Test
    fun `continues a loaded thread without reloading it`() {
        val transport = FakeAppServerTransport()
        val client = client(transport)
        try {
            assertTrue(client.startServer())
            val threadId = client.create().getOrThrow()
            client.execute(threadId, "first")

            val result = client.execute(threadId, "follow up")

            assertEquals("structured answer", result.getOrThrow())
            assertEquals(0, transport.methods.count { it == "thread/resume" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `unsubscribes an ended thread and reloads it on the next execution`() {
        val transport = FakeAppServerTransport()
        val client = client(transport)
        try {
            assertTrue(client.startServer())
            val threadId = client.create().getOrThrow()
            client.execute(threadId, "first")

            client.end(threadId)
            client.execute(threadId, "follow up")

            assertEquals(listOf(threadId), transport.unsubscribedThreads)
            assertEquals(1, transport.methods.count { it == "thread/resume" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejects an answer that does not match the required structure`() {
        val transport = FakeAppServerTransport(answerPayload = "plain text")
        val client = client(transport)
        try {
            assertTrue(client.startServer())

            val threadId = client.create().getOrThrow()
            val result = client.execute(threadId, "prompt")

            assertEquals("INVALID_STRUCTURED_ANSWER", result.exceptionOrNull()?.message)
        } finally {
            client.close()
        }
    }

    private fun client(transport: FakeAppServerTransport): CodexAppServerConversationClient =
        CodexAppServerConversationClient(
            config = CodexConversationConfig(
                executable = "unused",
                workDir = Files.createTempDirectory("codex-app-server-test-"),
                timeout = Duration.ofSeconds(5),
            ),
            transport = transport,
            availabilityProbe = { true },
        )

    private class FakeAppServerTransport(
        private val answerPayload: String = "{\"answer\":\"structured answer\"}",
    ) : AppServerTransport {
        override var isAlive: Boolean = false
            private set
        var startCount = 0
        val methods = mutableListOf<String>()
        val turnParams = mutableListOf<JsonObject>()
        val unsubscribedThreads = mutableListOf<String>()
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

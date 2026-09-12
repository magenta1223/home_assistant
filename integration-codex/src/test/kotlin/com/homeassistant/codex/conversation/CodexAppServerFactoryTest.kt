package com.homeassistant.codex.conversation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexAppServerFactoryTest {
    @Test
    fun `returns one initialized app server`() {
        val transport = InitializingTransport()

        val server = CodexAppServerFactory.create(
            config = config(),
            transport = transport,
            availabilityProbe = { true },
        )

        assertNotNull(server)
        assertEquals(1, transport.startCount)
        assertEquals(listOf("initialize", "initialized"), transport.methods)
        server.close()
    }

    @Test
    fun `closes resources when preparation fails`() {
        val transport = InitializingTransport()

        val server = CodexAppServerFactory.create(
            config = config(),
            transport = transport,
            availabilityProbe = { false },
        )

        assertNull(server)
        assertTrue(transport.closed)
        assertEquals(0, transport.startCount)
    }

    private fun config(): CodexConversationConfig = CodexConversationConfig(
        executable = "unused",
        workDir = Files.createTempDirectory("codex-app-server-factory-test-"),
        timeout = Duration.ofSeconds(5),
    )

    private class InitializingTransport : AppServerTransport {
        override var isAlive: Boolean = false
            private set
        var startCount = 0
        var closed = false
        val methods = mutableListOf<String>()
        private var onMessage: (String) -> Unit = {}

        override fun start(onMessage: (String) -> Unit, onClosed: () -> Unit): Boolean {
            startCount++
            isAlive = true
            this.onMessage = onMessage
            return true
        }

        override fun send(message: String) {
            val request = CODEX_JSON.parseToJsonElement(message) as JsonObject
            methods += request["method"]?.jsonPrimitive?.content ?: return
            val id = request["id"] ?: return
            onMessage(buildJsonObject {
                put("id", id)
                put("result", buildJsonObject {})
            }.toString())
        }

        override fun stop() {
            isAlive = false
        }

        override fun close() {
            closed = true
            isAlive = false
        }
    }
}

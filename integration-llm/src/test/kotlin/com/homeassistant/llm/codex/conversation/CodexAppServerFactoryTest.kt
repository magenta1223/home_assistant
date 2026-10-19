package com.homeassistant.llm.codex.conversation

import com.homeassistant.llm.codex.transport.CodexAppServerTransport
import com.homeassistant.llm.codex.transport.protocol.AppServerProtocol
import com.homeassistant.llm.codex.transport.protocol.ThreadResult
import com.homeassistant.llm.codex.transport.protocol.TurnStartResult
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
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
        assertEquals(1, transport.initializeCount)
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

    private class InitializingTransport : CodexAppServerTransport {
        var startCount = 0
        var initializeCount = 0
        var closed = false

        override fun start(listener: CodexAppServerTransport.Listener): Boolean {
            startCount++
            return true
        }

        override fun initialize(request: AppServerProtocol.Initialize): CompletableFuture<Unit> {
            initializeCount++
            return CompletableFuture.completedFuture(Unit)
        }

        override fun startThread(request: AppServerProtocol.ThreadStart): CompletableFuture<ThreadResult> =
            error("unused")

        override fun resumeThread(request: AppServerProtocol.ThreadResume): CompletableFuture<ThreadResult> =
            error("unused")

        override fun startTurn(request: AppServerProtocol.TurnStart): CompletableFuture<TurnStartResult> =
            error("unused")

        override fun interruptTurn(request: AppServerProtocol.TurnInterrupt): CompletableFuture<Unit> =
            error("unused")

        override fun unsubscribeThread(request: AppServerProtocol.ThreadUnsubscribe): CompletableFuture<Unit> =
            error("unused")

        override fun stop() = Unit

        override fun close() {
            closed = true
        }
    }
}

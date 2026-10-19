package com.homeassistant.llm.codex.conversation

import com.homeassistant.llm.codex.transport.CodexAppServerTransport
import com.homeassistant.llm.codex.transport.protocol.AppServerProtocol
import com.homeassistant.llm.codex.transport.protocol.AppServerThread
import com.homeassistant.llm.codex.transport.protocol.AppServerThreadItem
import com.homeassistant.llm.codex.transport.protocol.AppServerTurn
import com.homeassistant.llm.codex.transport.protocol.AppServerTurnReference
import com.homeassistant.llm.codex.transport.protocol.ThreadResult
import com.homeassistant.llm.codex.transport.protocol.TurnStartResult
import com.homeassistant.llm.conversation.ConversationClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultCodexAppServerTest {
    @Test
    fun `creates distinct threads without starting turns`() {
        val transport = FakeCodexAppServerTransport()
        val server = server(transport)
        try {
            val firstThread = server.begin().getOrThrow()
            val secondThread = server.begin().getOrThrow()

            assertEquals(1, transport.startCount)
            assertNotEquals(firstThread, secondThread)
            assertEquals(2, transport.methods.count { it == "thread/start" })
            assertEquals(0, transport.methods.count { it == "turn/start" })

            assertTrue(server.send(firstThread, "first prompt").isSuccess)
            assertTrue(server.send(secondThread, "second prompt").isSuccess)

            assertEquals(2, transport.methods.count { it == "turn/start" })
            transport.turnRequests.forEach { request ->
                val schema = request.outputSchema as JsonObject
                assertEquals("object", schema["type"]?.jsonPrimitive?.content)
                assertEquals("false", schema["additionalProperties"]?.jsonPrimitive?.content)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `checks the transport before using a previously ready app server`() {
        val transport = FakeCodexAppServerTransport()
        val server = server(transport)
        try {
            transport.makeUnavailable()

            val result = server.begin()

            assertEquals("APP_SERVER_UNAVAILABLE", result.exceptionOrNull()?.message)
            assertEquals(0, transport.methods.count { it == "thread/start" })
        } finally {
            server.close()
        }
    }

    @Test
    fun `continues a loaded thread without reloading it`() {
        val transport = FakeCodexAppServerTransport()
        val server = server(transport)
        try {
            val threadId = server.begin().getOrThrow()
            server.send(threadId, "first")

            val result = server.send(threadId, "follow up")

            assertEquals("structured answer", result.getOrThrow())
            assertEquals(0, transport.methods.count { it == "thread/resume" })
        } finally {
            server.close()
        }
    }

    @Test
    fun `unsubscribes an ended thread and reloads it on the next execution`() {
        val transport = FakeCodexAppServerTransport()
        val server = server(transport)
        try {
            val threadId = server.begin().getOrThrow()
            server.send(threadId, "first")

            server.end(threadId)
            server.send(threadId, "follow up")

            assertEquals(listOf(threadId), transport.unsubscribedThreads)
            assertEquals(1, transport.methods.count { it == "thread/resume" })
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects an answer that does not match the required structure`() {
        val transport = FakeCodexAppServerTransport(answerPayload = "plain text")
        val server = server(transport)
        try {
            val threadId = server.begin().getOrThrow()
            val result = server.send(threadId, "prompt")

            assertEquals("INVALID_STRUCTURED_ANSWER", result.exceptionOrNull()?.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects a protocol error instead of waiting for timeout`() {
        val transport = FakeCodexAppServerTransport(reportProtocolError = true)
        val server = server(transport)
        try {
            val threadId = server.begin().getOrThrow()

            val result = server.send(threadId, "prompt")

            assertEquals("INVALID_APP_SERVER_MESSAGE", result.exceptionOrNull()?.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun `structured answer rejects extra fields`() {
        assertNull(parseStructuredAnswer("""{"answer":"valid","extra":true}"""))
    }

    private fun server(transport: FakeCodexAppServerTransport): ConversationClient =
        requireNotNull(
            CodexAppServerFactory.prepare(
                DefaultCodexAppServer(
                    config = CodexConversationConfig(
                        executable = "unused",
                        workDir = Files.createTempDirectory("codex-app-server-test-"),
                        timeout = Duration.ofSeconds(5),
                    ),
                    transport = transport,
                    availabilityProbe = { true },
                ),
            ),
        )

    private class FakeCodexAppServerTransport(
        private val answerPayload: String = "{\"answer\":\"structured answer\"}",
        private val reportProtocolError: Boolean = false,
    ) : CodexAppServerTransport {
        var startCount = 0
            private set
        val methods = mutableListOf<String>()
        val turnRequests = mutableListOf<AppServerProtocol.TurnStart>()
        val unsubscribedThreads = mutableListOf<String>()
        private var listener: CodexAppServerTransport.Listener? = null
        private var running = false
        private var closed = false
        private var startAvailable = true
        private var nextThread = 1

        override fun start(listener: CodexAppServerTransport.Listener): Boolean {
            if (closed || !startAvailable) return false
            if (!running) startCount++
            running = true
            this.listener = listener
            return true
        }

        fun makeUnavailable() {
            running = false
            startAvailable = false
        }

        override fun initialize(request: AppServerProtocol.Initialize): CompletableFuture<Unit> {
            methods += request.method
            return CompletableFuture.completedFuture(Unit)
        }

        override fun startThread(request: AppServerProtocol.ThreadStart): CompletableFuture<ThreadResult> {
            methods += request.method
            val threadId = "00000000-0000-0000-0000-${nextThread++.toString().padStart(12, '0')}"
            return CompletableFuture.completedFuture(ThreadResult(AppServerThread(threadId)))
        }

        override fun resumeThread(request: AppServerProtocol.ThreadResume): CompletableFuture<ThreadResult> {
            methods += request.method
            return CompletableFuture.completedFuture(ThreadResult(AppServerThread(request.threadId)))
        }

        override fun startTurn(request: AppServerProtocol.TurnStart): CompletableFuture<TurnStartResult> {
            methods += request.method
            turnRequests += request
            val turnId = "turn-${turnRequests.size}"
            val result = CompletableFuture.completedFuture(TurnStartResult(AppServerTurnReference(turnId)))
            if (reportProtocolError) {
                listener?.onProtocolError()
                return result
            }
            listener?.onItemCompleted(
                AppServerProtocol.ItemCompleted(
                    threadId = request.threadId,
                    turnId = turnId,
                    item = AppServerThreadItem(type = "agentMessage", text = answerPayload),
                    completedAtMs = 1,
                ),
            )
            listener?.onTurnCompleted(
                AppServerProtocol.TurnCompleted(
                    threadId = request.threadId,
                    turn = AppServerTurn(
                        id = turnId,
                        status = "completed",
                        items = emptyList(),
                        error = null,
                    ),
                ),
            )
            return result
        }

        override fun interruptTurn(request: AppServerProtocol.TurnInterrupt): CompletableFuture<Unit> {
            methods += request.method
            return CompletableFuture.completedFuture(Unit)
        }

        override fun unsubscribeThread(request: AppServerProtocol.ThreadUnsubscribe): CompletableFuture<Unit> {
            methods += request.method
            unsubscribedThreads += request.threadId
            return CompletableFuture.completedFuture(Unit)
        }

        override fun stop() {
            running = false
        }

        override fun close() {
            closed = true
            running = false
        }
    }
}

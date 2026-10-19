package com.homeassistant.codex.completion

import com.homeassistant.codex.transport.DefaultCodexAppServerTransport
import com.homeassistant.codex.transport.process.ProcessTransport
import com.homeassistant.codex.transport.protocol.AppServerProtocol
import com.homeassistant.codex.transport.protocol.AppServerThreadItem
import com.homeassistant.codex.transport.protocol.AppServerTurn
import com.homeassistant.codex.transport.protocol.CODEX_JSON
import com.homeassistant.codex.transport.protocol.LocalImageUserInput
import com.homeassistant.codex.transport.protocol.TextUserInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexCliClientTest {
    @Test
    fun `completion uses existing transport with sol high schema and an ephemeral thread`() = runBlocking {
        val process = RecordingProcess()
        val schema = """{"type":"object","properties":{"memories":{"type":"array"}}}"""

        assertEquals("{\"memories\":[]}", client(process).complete("system prompt", "source records", schema))

        assertEquals(listOf("codex-test", "app-server", "--stdio"), process.command)
        assertEquals(listOf("initialize", "initialized", "thread/start", "turn/start"), process.methods)
        assertTrue(process.thread.ephemeral)
        assertEquals("gpt-5.6-sol", process.thread.model)
        assertEquals("high", process.thread.config.modelReasoningEffort)
        assertEquals("gpt-5.6-sol", process.turn.model)
        assertEquals("high", process.turn.effort)
        assertEquals(CODEX_JSON.parseToJsonElement(schema), process.turn.outputSchema)
        assertEquals("system prompt\n\n[user]\nsource records", (process.turn.input.single() as TextUserInput).text)
        assertClosedAndCleaned(process)
    }

    @Test
    fun `image completion attaches readable local images then removes them`() = runBlocking {
        val original = byteArrayOf(1, 2, 3)
        val process = RecordingProcess().apply {
            onTurn = {
                val image = turn.input.filterIsInstance<LocalImageUserInput>().single()
                assertEquals("input-1.png", Path.of(image.path).fileName.toString())
                assertContentEquals(original, Files.readAllBytes(Path.of(image.path)))
                finish()
            }
        }

        client(process).completeWithImages("system", "image", "{}", listOf(CodexImage("photo.png", original)))

        assertClosedAndCleaned(process)
    }

    @Test
    fun `failed turn and process exit fail the request and release its resources`() = runBlocking {
        for (exit in listOf(false, true)) {
            val process = RecordingProcess().apply {
                onTurn = { if (exit) listener.onProcessExited() else finish("failed") }
            }
            assertFailsWith<ExecutionException> { client(process).complete("system", "records", "{}") }
            assertClosedAndCleaned(process)
        }
    }

    @Test
    fun `timeout closes the process and removes request files`() = runBlocking {
        val process = RecordingProcess().apply { onTurn = {} }

        assertFailsWith<TimeoutException> { client(process, 100).complete("system", "records", "{}") }

        assertClosedAndCleaned(process)
    }

    @Test
    fun `cancellation closes the process and removes request files`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val process = RecordingProcess().apply { onTurn = { started.complete(Unit) } }
        val analysis = launch { client(process).complete("system", "records", "{}") }

        withTimeout(5_000) { started.await() }
        withTimeout(5_000) { analysis.cancelAndJoin() }

        assertTrue(analysis.isCancelled)
        assertClosedAndCleaned(process)
    }

    private fun client(process: RecordingProcess, timeoutMillis: Long = 600_000) = CodexCliClient(
        executable = "codex-test",
        timeoutMillis = timeoutMillis,
        transportFactory = { command, directory ->
            process.command = command
            process.directory = directory
            DefaultCodexAppServerTransport(process)
        },
    )

    private fun assertClosedAndCleaned(process: RecordingProcess) {
        assertTrue(process.closed)
        assertFalse(Files.exists(process.directory))
    }

    private class RecordingProcess : ProcessTransport {
        lateinit var listener: ProcessTransport.Listener
        lateinit var command: List<String>
        lateinit var directory: Path
        lateinit var thread: AppServerProtocol.ThreadStart
        lateinit var turn: AppServerProtocol.TurnStart
        val methods = mutableListOf<String>()
        var closed = false
        var onTurn: () -> Unit = { finish() }

        override fun start(listener: ProcessTransport.Listener): Boolean {
            this.listener = listener
            return true
        }

        override fun send(message: String) {
            val json = CODEX_JSON.parseToJsonElement(message).jsonObject
            val method = json.getValue("method").jsonPrimitive.content
            methods += method
            val params = json.getValue("params")
            val id = json["id"] ?: return
            when (method) {
                "initialize" -> respond(id, "{}")
                "thread/start" -> {
                    thread = CODEX_JSON.decodeFromJsonElement(AppServerProtocol.ThreadStart.serializer(), params)
                    respond(id, """{"thread":{"id":"thread-1"}}""")
                }
                "turn/start" -> {
                    turn = CODEX_JSON.decodeFromJsonElement(AppServerProtocol.TurnStart.serializer(), params)
                    respond(id, """{"turn":{"id":"turn-1"}}""")
                    onTurn()
                }
            }
        }

        fun finish(status: String = "completed") {
            val event = AppServerProtocol.TurnCompleted(
                "thread-1",
                AppServerTurn("turn-1", status, listOf(AppServerThreadItem("agentMessage", "{\"memories\":[]}")), null),
            )
            listener.onMessage(buildJsonObject {
                put("method", AppServerProtocol.TurnCompleted.METHOD)
                put("params", CODEX_JSON.encodeToJsonElement(AppServerProtocol.TurnCompleted.serializer(), event))
            }.toString())
        }

        private fun respond(id: JsonElement, result: String) {
            listener.onMessage(buildJsonObject {
                put("id", id)
                put("result", CODEX_JSON.parseToJsonElement(result))
            }.toString())
        }

        override fun stop() = Unit
        override fun close() { closed = true }
    }
}

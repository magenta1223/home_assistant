package com.homeassistant.llm.codex.completion

import com.homeassistant.llm.codex.conversation.CodexConversationConfig
import com.homeassistant.llm.codex.conversation.probeCodexVersion
import com.homeassistant.llm.codex.transport.CodexAppServerTransport
import com.homeassistant.llm.codex.transport.DefaultCodexAppServerTransport
import com.homeassistant.llm.codex.transport.process.StdioProcessTransport
import com.homeassistant.llm.codex.transport.protocol.AppServerProtocol
import com.homeassistant.llm.codex.transport.protocol.ClientInfo
import com.homeassistant.llm.codex.transport.protocol.LocalImageUserInput
import com.homeassistant.llm.codex.transport.protocol.TextUserInput
import com.homeassistant.llm.codex.transport.protocol.ThreadConfig
import com.homeassistant.llm.completion.CompletionClient
import com.homeassistant.llm.completion.CompletionImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeBytes

internal class CodexCliClient(
    private val executable: String = CodexExecutableFactory.get(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val transportFactory: (List<String>, Path) -> CodexAppServerTransport =
        { command, workDir -> DefaultCodexAppServerTransport(StdioProcessTransport(command, workDir)) },
) : CompletionClient {
    override fun isAvailable(): Boolean =
        CodexConversationConfig.local(Duration.ofMillis(timeoutMillis), executable)?.let(::probeCodexVersion) ?: false

    override suspend fun complete(
        system: String,
        userMessage: String,
        outputSchema: String,
        images: List<CompletionImage>,
    ): String = runInterruptible(Dispatchers.IO) {
        val schema = Json.parseToJsonElement(outputSchema)
        val workingDirectory = Files.createTempDirectory("homeassistant-codex-")
        try {
            val input = listOf(TextUserInput("$system\n\n[user]\n$userMessage")) +
                images.mapIndexed { index, image ->
                    val extension = image.fileName.substringAfterLast('.', "png").lowercase()
                        .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "png"
                    val file = workingDirectory.resolve("input-${index + 1}.$extension")
                    file.writeBytes(image.bytes)
                    LocalImageUserInput(file.toString())
                }
            transportFactory(listOf(executable, "app-server", "--stdio"), workingDirectory).use { transport ->
                val result = CompletionResult()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                check(transport.start(result)) { "Codex app-server could not start" }
                await(transport.initialize(AppServerProtocol.Initialize(ClientInfo("home_second_brain", "Home Second Brain", "1"))), deadline)
                val thread = await(transport.startThread(AppServerProtocol.ThreadStart(
                    model = MEMORY_GENERATION_MODEL,
                    cwd = workingDirectory.toString(),
                    config = ThreadConfig(modelReasoningEffort = MEMORY_GENERATION_REASONING_EFFORT),
                    ephemeral = true,
                )), deadline).thread
                await(transport.startTurn(AppServerProtocol.TurnStart(
                    threadId = thread.id,
                    input = input,
                    cwd = workingDirectory.toString(),
                    model = MEMORY_GENERATION_MODEL,
                    effort = MEMORY_GENERATION_REASONING_EFFORT,
                    outputSchema = schema,
                )), deadline)
                await(result.completed, deadline)
            }
        } finally {
            Files.walk(workingDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun <T> await(response: CompletableFuture<T>, deadline: Long): T =
        response.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)

    private class CompletionResult : CodexAppServerTransport.Listener {
        val completed = CompletableFuture<String>()
        private var answer: String? = null

        override fun onItemCompleted(event: AppServerProtocol.ItemCompleted) {
            if (event.item.type == "agentMessage") event.item.text?.let { answer = it }
        }

        override fun onTurnCompleted(event: AppServerProtocol.TurnCompleted) {
            val text = event.turn.items.lastOrNull { it.type == "agentMessage" && it.text != null }?.text ?: answer
            when {
                event.turn.status != "completed" -> fail("Codex turn ${event.turn.status}")
                text == null -> fail("Codex app-server did not return its final response")
                else -> completed.complete(text)
            }
        }

        override fun onThreadClosed(event: AppServerProtocol.ThreadClosed) = fail("Codex thread closed")
        override fun onProtocolError() = fail("Invalid Codex app-server message")
        override fun onAppServerExited() = fail("Codex app-server exited")

        private fun fail(message: String) {
            completed.completeExceptionally(IllegalStateException(message))
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        const val MEMORY_GENERATION_MODEL = "gpt-5.6-sol"
        const val MEMORY_GENERATION_REASONING_EFFORT = "high"
    }
}

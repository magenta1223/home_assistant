package com.homeassistant.adapter.outbound.codex

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexCliClientTest {
    @Test
    fun `memory generation uses sol with high reasoning effort`() = runBlocking {
        val executor = RecordingProcessExecutor()
        val client = CodexCliClient(
            executable = "codex-test",
            processExecutor = executor,
        )

        val result = client.complete(
            system = "system prompt",
            userMessage = "source records",
            outputSchema = "{}",
        )

        assertEquals("{\"memories\":[]}", result)
        assertTrue(executor.command.contains("--ignore-user-config"))
        assertTrue(executor.command.contains("--ignore-rules"))
        assertTrue(
            executor.command.windowed(2).contains(
                listOf("--model", "gpt-5.6-sol"),
            ),
        )
        assertTrue(
            executor.command.windowed(2).contains(
                listOf("--config", "model_reasoning_effort=\"high\""),
            ),
        )
        assertEquals(10 * 60 * 1_000L, executor.timeoutMillis)
    }

    private class RecordingProcessExecutor : CodexProcessExecutor {
        lateinit var command: List<String>
        var timeoutMillis: Long = 0

        override fun execute(
            command: List<String>,
            workingDirectory: Path,
            timeoutMillis: Long,
            stdin: String,
        ): CodexProcessResult {
            this.command = command
            this.timeoutMillis = timeoutMillis
            val outputPath = Path.of(command[command.indexOf("--output-last-message") + 1])
            outputPath.writeText("{\"memories\":[]}")
            return CodexProcessResult(exitCode = 0, stderr = "")
        }
    }
}

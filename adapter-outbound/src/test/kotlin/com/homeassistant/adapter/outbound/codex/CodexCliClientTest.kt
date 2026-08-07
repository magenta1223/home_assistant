package com.homeassistant.adapter.outbound.codex

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CodexCliClientTest {
    @Test
    fun `uses npm command shim on Windows`() {
        assertEquals("codex.cmd", defaultCodexExecutable("Windows 11"))
        assertEquals("codex", defaultCodexExecutable("Linux"))
    }

    @Test
    fun `executes codex in an isolated directory with structured output`() = runBlocking {
        val executor = RecordingExecutor { command, _ ->
            val outputPath = command[command.indexOf("--output-last-message") + 1]
            java.nio.file.Path.of(outputPath).writeText("""{"memories":[]}""")
            CodexProcessResult(exitCode = 0, stderr = "")
        }
        val client = CodexCliClient(executable = "codex-test", processExecutor = executor)

        val response = client.complete(
            system = "Analyze the source",
            userMessage = "family message",
            outputSchema = """{"type":"object"}""",
        )

        assertEquals("""{"memories":[]}""", response)
        assertEquals("codex-test", executor.command.first())
        assertContains(executor.command, "--ephemeral")
        assertContains(executor.command, "read-only")
        assertContains(executor.command, "--skip-git-repo-check")
        assertEquals("-", executor.command.last())
        assertContains(executor.stdin, "Analyze the source")
        assertContains(executor.stdin, "[user]")
        assertContains(executor.stdin, "family message")
        assertEquals("""{"type":"object"}""", executor.schemaContent)
        assertFalse(Files.exists(executor.workingDirectory))
    }

    @Test
    fun `reports codex process failures`() {
        val client = CodexCliClient(
            processExecutor = RecordingExecutor { _, _ ->
                CodexProcessResult(exitCode = 7, stderr = "authentication required")
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            runBlocking { client.complete("system", "message", outputSchema = "{}") }
        }

        assertContains(error.message.orEmpty(), "code 7")
        assertContains(error.message.orEmpty(), "authentication required")
    }

    @Test
    fun `reports codex process timeout`() {
        val client = CodexCliClient(
            timeoutMillis = 1234,
            processExecutor = RecordingExecutor { _, _ ->
                CodexProcessResult(exitCode = -1, stderr = "", timedOut = true)
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            runBlocking { client.complete("system", "message", outputSchema = "{}") }
        }

        assertContains(error.message.orEmpty(), "timed out after 1234ms")
    }
}

private class RecordingExecutor(
    private val result: (List<String>, String) -> CodexProcessResult,
) : CodexProcessExecutor {
    lateinit var command: List<String>
    lateinit var workingDirectory: java.nio.file.Path
    lateinit var schemaContent: String
    lateinit var stdin: String

    override fun execute(
        command: List<String>,
        workingDirectory: java.nio.file.Path,
        timeoutMillis: Long,
        stdin: String,
    ): CodexProcessResult {
        this.command = command
        this.workingDirectory = workingDirectory
        this.stdin = stdin
        val schemaPath = command[command.indexOf("--output-schema") + 1]
        schemaContent = java.nio.file.Path.of(schemaPath).readText()
        return result(command, stdin)
    }
}

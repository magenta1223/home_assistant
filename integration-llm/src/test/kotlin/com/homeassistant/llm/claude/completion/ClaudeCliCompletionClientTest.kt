package com.homeassistant.llm.claude.completion

import com.homeassistant.llm.claude.cli.ClaudeProcessOutput
import com.homeassistant.llm.claude.cli.ClaudeProcessRunner
import com.homeassistant.llm.completion.CompletionImage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeCliCompletionClientTest {
    @Test
    fun `completion sends the system prompt file, compact schema and stdin message without persisting a session`() =
        runBlocking {
            val runner = RecordingRunner(RESULT)

            val response = client(runner).complete(
                "system prompt",
                "source records",
                """{ "type": "object" }""",
            )

            assertEquals("""{"memories":[]}""", response)
            val command = runner.command
            assertEquals("claude-test", command.first())
            assertEquals("claude-opus-5-5", command.after("--model"))
            assertEquals("high", command.after("--effort"))
            assertEquals("""{"type":"object"}""", command.after("--json-schema"))
            assertTrue("--no-session-persistence" in command)
            assertEquals("system prompt", runner.systemPrompt)
            assertTrue(runner.stdin.contains("source records"))
            assertFalse(Files.exists(runner.workingDirectory))
        }

    @Test
    fun `images are sent inline instead of as files`() = runBlocking {
        val runner = RecordingRunner(RESULT)

        client(runner).completeWithImages("system", "image", "{}", listOf(CompletionImage("photo.png", byteArrayOf(1))))

        assertTrue(runner.stdin.contains("\"media_type\":\"image/png\""))
    }

    @Test
    fun `plain result text is returned when no structured output exists`() = runBlocking {
        val runner = RecordingRunner("""{"type":"result","is_error":false,"result":"{\"a\":1}"}""")

        assertEquals("""{"a":1}""", client(runner).complete("s", "u", "{}"))
    }

    @Test
    fun `failure, missing result and timeout fail the request and remove the working directory`() = runBlocking {
        val outputs = listOf(
            ClaudeProcessOutput(1, """{"type":"result","is_error":true,"result":"auth"}""", ""),
            ClaudeProcessOutput(1, "", "crash"),
            ClaudeProcessOutput(-1, "", "", timedOut = true),
        )
        for (output in outputs) {
            val runner = RecordingRunner(output)
            assertFailsWith<IllegalStateException> { client(runner).complete("s", "u", "{}") }
            assertFalse(Files.exists(runner.workingDirectory))
        }
    }

    private fun client(runner: ClaudeProcessRunner) =
        ClaudeCliCompletionClient(executable = "claude-test", timeout = Duration.ofSeconds(5), runner = runner)

    private fun List<String>.after(flag: String): String = this[indexOf(flag) + 1]

    private class RecordingRunner(private val output: ClaudeProcessOutput) : ClaudeProcessRunner {
        constructor(stdout: String) : this(ClaudeProcessOutput(0, stdout, ""))

        lateinit var command: List<String>
        lateinit var workingDirectory: Path
        lateinit var stdin: String
        lateinit var systemPrompt: String

        override fun run(command: List<String>, workingDirectory: Path, stdin: String, timeout: Duration): ClaudeProcessOutput {
            this.command = command
            this.workingDirectory = workingDirectory
            this.stdin = stdin
            systemPrompt = Path.of(command[command.indexOf("--system-prompt-file") + 1]).readText()
            return output
        }
    }

    private companion object {
        const val RESULT = """{"type":"result","is_error":false,"result":"","structured_output":{"memories":[]}}"""
    }
}

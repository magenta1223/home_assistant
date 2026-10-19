package com.homeassistant.llm.claude.conversation

import com.homeassistant.llm.claude.cli.ClaudeProcessOutput
import com.homeassistant.llm.claude.cli.ClaudeProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeConversationClientTest {
    private val temporaryDirectory: Path = Files.createTempDirectory("claude-conversation-test")

    @AfterTest
    fun cleanUp() {
        Files.walk(temporaryDirectory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test
    fun `first turn creates the session and later turns resume it in the fixed working directory`() {
        val runner = RecordingRunner()
        val client = client(runner)
        val conversation = client.begin().getOrThrow()

        assertEquals("first", client.send(conversation, "hello").getOrThrow())
        assertEquals("first", client.send(conversation, "again").getOrThrow())

        assertEquals(conversation, runner.commands[0].after("--session-id"))
        assertEquals(conversation, runner.commands[1].after("--resume"))
        assertTrue(runner.commands.none { "--no-session-persistence" in it })
        assertEquals(setOf(config().workDir), runner.workingDirectories.toSet())
        assertTrue(Path.of(runner.commands[0].after("--system-prompt-file")).readText().isNotBlank())
        assertTrue(runner.stdins[1].contains("again"))
    }

    @Test
    fun `a session id this process did not create is resumed`() {
        val runner = RecordingRunner()
        val restored = "3f2b8c1e-6a4d-4e2f-9b1a-7c5d3e2f1a0b"

        client(runner).send(restored, "hello").getOrThrow()

        assertEquals(restored, runner.commands.single().after("--resume"))
    }

    @Test
    fun `a failed first turn retries session creation`() {
        val runner = RecordingRunner(ClaudeProcessOutput(-1, "", "", timedOut = true))
        val client = client(runner)
        val conversation = client.begin().getOrThrow()

        assertEquals("TIMEOUT", client.send(conversation, "hello").failureCategory())
        runner.output = ClaudeProcessOutput(0, ANSWER, "")
        client.send(conversation, "hello").getOrThrow()

        assertTrue(runner.commands.all { "--session-id" in it })
    }

    @Test
    fun `invalid requests and failed results are reported by category`() {
        val runner = RecordingRunner(ClaudeProcessOutput(1, """{"type":"result","is_error":true}""", ""))
        val client = client(runner)
        val conversation = client.begin().getOrThrow()

        assertEquals("INVALID_CONVERSATION_ID", client.send("not-a-uuid", "hi").failureCategory())
        assertEquals("EMPTY_PROMPT", client.send(conversation, " ").failureCategory())
        assertEquals("TURN_FAILED", client.send(conversation, "hi").failureCategory())
        runner.output = ClaudeProcessOutput(0, "", "")
        assertEquals("NO_RESULT", client.send(conversation, "hi").failureCategory())
        runner.output = ClaudeProcessOutput(0, """{"type":"result","is_error":false,"result":" "}""", "")
        assertEquals("EMPTY_ANSWER", client.send(conversation, "hi").failureCategory())
        client.close()
        assertEquals("CLOSED", client.send(conversation, "hi").failureCategory())
        assertTrue(client.begin().isFailure)
    }

    @Test
    fun `a conversation runs one turn at a time`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = ClaudeProcessRunner { _, _, _, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            ClaudeProcessOutput(0, ANSWER, "")
        }
        val client = client(runner)
        val conversation = client.begin().getOrThrow()
        val first = thread { client.send(conversation, "first") }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals("CONVERSATION_BUSY", client.send(conversation, "second").failureCategory())
        release.countDown()
        first.join()
    }


    private fun config() = ClaudeConversationConfig.local(Duration.ofSeconds(5), "claude-test", temporaryDirectory)!!

    private fun client(runner: ClaudeProcessRunner) = ClaudeConversationClient(config(), runner)

    private fun Result<String>.failureCategory(): String? =
        (exceptionOrNull() as? ClaudeConversationException)?.category

    private fun List<String>.after(flag: String): String = this[indexOf(flag) + 1]

    private class RecordingRunner(var output: ClaudeProcessOutput = ClaudeProcessOutput(0, ANSWER, "")) :
        ClaudeProcessRunner {
        val commands = mutableListOf<List<String>>()
        val workingDirectories = mutableListOf<Path>()
        val stdins = mutableListOf<String>()

        override fun run(command: List<String>, workingDirectory: Path, stdin: String, timeout: Duration): ClaudeProcessOutput {
            commands.add(command)
            workingDirectories.add(workingDirectory)
            stdins.add(stdin)
            return output
        }
    }

    private companion object {
        const val ANSWER = """{"type":"result","is_error":false,"result":" first "}"""
    }
}

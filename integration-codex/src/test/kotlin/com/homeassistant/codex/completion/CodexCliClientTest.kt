package com.homeassistant.codex.completion

import com.homeassistant.codex.subprocess.ProcessExecutor
import com.homeassistant.codex.subprocess.ProcessResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun `image completion attaches copied image files to Codex`() = runBlocking {
        val executor = RecordingProcessExecutor()
        val client = CodexCliClient(executable = "codex-test", processExecutor = executor)
        val original = byteArrayOf(1, 2, 3)

        client.completeWithImages(
            system = "system prompt",
            userMessage = "interpret image",
            outputSchema = "{}",
            images = listOf(CodexImage("photo.png", original)),
        )

        val imagePath = Path.of(executor.command[executor.command.indexOf("--image") + 1])
        assertEquals("input-1.png", imagePath.fileName.toString())
        assertContentEquals(original, executor.imageBytes)
    }

    private class RecordingProcessExecutor : ProcessExecutor {
        lateinit var command: List<String>
        var timeoutMillis: Long = 0
        var imageBytes: ByteArray? = null

        override fun execute(
            command: List<String>,
            workingDirectory: Path,
            timeoutMillis: Long,
            stdin: String,
        ): ProcessResult {
            this.command = command
            this.timeoutMillis = timeoutMillis
            command.indexOf("--image").takeIf { it >= 0 }?.let { imageIndex ->
                imageBytes = Files.readAllBytes(Path.of(command[imageIndex + 1]))
            }
            val outputPath = Path.of(command[command.indexOf("--output-last-message") + 1])
            outputPath.writeText("{\"memories\":[]}")
            return ProcessResult(exitCode = 0, stderr = "")
        }
    }
}

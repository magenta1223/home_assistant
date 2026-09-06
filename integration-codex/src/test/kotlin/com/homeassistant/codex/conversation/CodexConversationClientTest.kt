package com.homeassistant.codex.conversation

import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class CodexConversationClientTest {
    @Test
    fun `availability probe allows a six second cold start`() {
        val temporaryDirectory = Files.createTempDirectory("slow-codex-version-")
        val executable = createSlowCodexExecutable(temporaryDirectory)
        val client = ConversationClientFactory.create(
            executable = executable,
            temporaryDirectory = temporaryDirectory,
            timeout = Duration.ofMinutes(10),
        )

        assertTrue(requireNotNull(client).isAvailable())
    }

    private fun createSlowCodexExecutable(directory: java.nio.file.Path): String {
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        return if (isWindows) {
            directory.resolve("slow-codex.cmd").also { script ->
                script.writeText(
                    """
                    @echo off
                    powershell.exe -NoProfile -NonInteractive -Command "Start-Sleep -Seconds 6"
                    echo codex-cli 1.2.3
                    """.trimIndent(),
                )
            }.toString()
        } else {
            directory.resolve("slow-codex").also { script ->
                script.writeText(
                    """
                    #!/bin/sh
                    sleep 6
                    echo codex-cli 1.2.3
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)
            }.toString()
        }
    }
}

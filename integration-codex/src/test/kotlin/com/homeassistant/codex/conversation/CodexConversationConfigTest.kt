package com.homeassistant.codex.conversation

import java.time.Duration

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexConversationConfigTest {
    @Test
    fun `local configuration needs no service credentials`() {
        val temporaryDirectory = Files.createTempDirectory("codex-conversation-config-")

        val config = CodexConversationConfig.local(
            timeout = Duration.ofSeconds(600),
            executable = "local-codex",
            temporaryDirectory = temporaryDirectory,
        )

        assertNotNull(config)
        assertEquals("local-codex", config.executable)
        assertEquals(600L, config.timeout.seconds)
        assertEquals("gpt-5.6-luna", config.model)
        assertEquals("medium", config.reasoningEffort)
        assertTrue(Files.isDirectory(config.workDir))
    }

    @Test
    fun `local configuration honors a positive timeout override`() {
        val temporaryDirectory = Files.createTempDirectory("codex-conversation-config-")

        val config = CodexConversationConfig.local(
            timeout = Duration.ofSeconds(42),
            executable = "local-codex",
            temporaryDirectory = temporaryDirectory,
        )

        assertEquals(42L, assertNotNull(config).timeout.seconds)
    }

    @Test
    fun `local configuration rejects a non-positive timeout`() {
        val temporaryDirectory = Files.createTempDirectory("codex-conversation-config-")

        val config = CodexConversationConfig.local(
            timeout = Duration.ZERO,
            executable = "local-codex",
            temporaryDirectory = temporaryDirectory,
        )

        assertNull(config)
    }
}

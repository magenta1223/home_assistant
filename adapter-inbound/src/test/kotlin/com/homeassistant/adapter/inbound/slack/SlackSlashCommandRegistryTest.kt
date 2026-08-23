package com.homeassistant.adapter.inbound.slack

import com.slack.api.bolt.App
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SlackSlashCommandRegistryTest {
    @Test
    fun `registry accepts independently registered commands at scale`() {
        SlackSlashCommandRegistry(
            (1..100).map { TestCommand("/command-$it", setOf("callback-$it")) },
        )
    }

    @Test
    fun `registry rejects duplicate commands and interaction callbacks`() {
        assertFailsWith<IllegalArgumentException> {
            SlackSlashCommandRegistry(listOf(TestCommand("/same"), TestCommand("/same")))
        }
        assertFailsWith<IllegalArgumentException> {
            SlackSlashCommandRegistry(
                listOf(
                    TestCommand("/first", setOf("same-callback")),
                    TestCommand("/second", setOf("same-callback")),
                ),
            )
        }
    }

    private class TestCommand(
        override val commandName: String,
        override val interactionCallbackIds: Set<String> = emptySet(),
    ) : SlackSlashCommand {
        override fun register(app: App) = Unit
    }
}

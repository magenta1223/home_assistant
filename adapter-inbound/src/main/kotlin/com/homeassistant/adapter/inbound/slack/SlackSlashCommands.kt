package com.homeassistant.adapter.inbound.slack

import com.slack.api.bolt.App

/** A self-contained slash command and its supporting Slack interaction listeners. */
internal interface SlackSlashCommand {
    val commandName: String
    val interactionCallbackIds: Set<String>

    fun register(app: App)
}

/** Registers independent slash-command features while rejecting ambiguous Slack routes. */
internal class SlackSlashCommandRegistry(
    private val commands: List<SlackSlashCommand>,
) {
    init {
        commands.forEach { command ->
            require(command.commandName.startsWith('/') && command.commandName.length > 1) {
                "Slack command names must start with '/': ${command.commandName}"
            }
        }
        requireUnique(commands.map(SlackSlashCommand::commandName), "Slack command")
        requireUnique(commands.flatMap { it.interactionCallbackIds }, "Slack interaction callback")
    }

    fun register(app: App) {
        commands.forEach { it.register(app) }
    }

    private fun requireUnique(values: List<String>, label: String) {
        val duplicate = values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicate == null) { "$label is registered more than once: $duplicate" }
    }
}

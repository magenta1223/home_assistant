package com.homeassistant.app.slack

object SlackClientFactory {
    fun create(botToken: String): SlackClient =
        CompositeSlackClient(
            fileClient = SlackApiComponentFactory.file(botToken),
            messageClient = SlackApiComponentFactory.message(botToken),
            modalClient = SlackApiComponentFactory.modal(botToken),
        )
}

private class CompositeSlackClient(
    private val fileClient: SlackFileClient,
    private val messageClient: SlackMessageClient,
    private val modalClient: SlackModalClient,
) : SlackClient,
    SlackFileClient by fileClient,
    SlackMessageClient by messageClient,
    SlackModalClient by modalClient

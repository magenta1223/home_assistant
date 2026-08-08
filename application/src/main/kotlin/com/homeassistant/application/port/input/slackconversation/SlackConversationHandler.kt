package com.homeassistant.application.port.input.slackconversation

data class SlackConversationMessage(
    val teamId: String,
    val slackUserId: String,
    val channelId: String,
    val messageTs: String,
    val text: String,
)

/** Handles one authenticated Slack direct message. */
fun interface SlackConversationHandler {
    fun execute(message: SlackConversationMessage)
}

package com.homeassistant.adapter.inbound.slack

import com.slack.api.bolt.App
import com.slack.api.model.event.MessageEvent

internal class SlackListeners(
    private val conversationService: SlackConversationService?,
) {
    fun register(app: App) {
        conversationService?.let { registerConversation(app, it) }
    }

    private fun registerConversation(app: App, service: SlackConversationService) {
        app.event(MessageEvent::class.java) { payload, ctx ->
            directMessage(payload.teamId, payload.event)?.let(service::submit)
            ctx.ack()
        }
    }

    private fun directMessage(teamId: String?, event: MessageEvent): SlackDirectMessage? {
        val resolvedTeamId = teamId?.takeIf { it.isNotBlank() } ?: return null
        if (event.channelType != "im") return null
        if (!event.botId.isNullOrBlank()) return null
        if (!event.subtype.isNullOrBlank()) return null
        val userId = event.user?.takeIf { it.isNotBlank() } ?: return null
        val channelId = event.channel?.takeIf { it.isNotBlank() } ?: return null
        val messageTs = event.ts?.takeIf { it.isNotBlank() } ?: return null
        val text = event.text?.takeIf { it.isNotBlank() } ?: return null
        return SlackDirectMessage(
            teamId = resolvedTeamId,
            slackUserId = userId,
            channelId = channelId,
            messageTs = messageTs,
            text = text,
        )
    }
}

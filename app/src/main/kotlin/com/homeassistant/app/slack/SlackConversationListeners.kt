package com.homeassistant.app.slack

import com.slack.api.bolt.App
import com.slack.api.model.event.MessageEvent

class SlackConversationListeners(
    private val conversationService: SlackConversationService,
) {
    fun register(app: App) {
        app.event(MessageEvent::class.java) { payload, ctx ->
            SlackDirectMessageIngress.from(payload.teamId, payload.event)?.let {
                conversationService.submit(it)
            }
            ctx.ack()
        }
    }
}

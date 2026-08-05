package com.homeassistant.adapter.inbound.slack

import com.slack.api.bolt.App
import com.slack.api.model.event.MessageEvent

interface SlackListenerRegistrar {
    fun register(app: App)
}

internal class SlackConversationListeners(
    private val conversationService: SlackConversationHandler,
) : SlackListenerRegistrar {
    override fun register(app: App) {
        app.event(MessageEvent::class.java) { payload, ctx ->
            SlackDirectMessageIngress.from(payload.teamId, payload.event)?.let {
                conversationService.submit(it)
            }
            ctx.ack()
        }
    }
}

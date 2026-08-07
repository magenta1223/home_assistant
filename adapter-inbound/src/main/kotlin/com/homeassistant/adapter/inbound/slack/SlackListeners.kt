package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.slackconversation.handle.SlackConversationMessage
import com.slack.api.bolt.App
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.MessageFileShareEvent
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService

internal class SlackListeners(
    private val config: SlackConfig,
    private val workflow: SlackKakaoAnalysisWorkflow,
    private val executor: ExecutorService,
    private val conversationService: SlackConversationService?,
) {
    fun register(app: App) {
        registerKakaoImport(app)
        conversationService?.let { registerConversation(app, it) }
    }

    private fun registerKakaoImport(app: App) {
        app.event(MessageFileShareEvent::class.java) { payload, ctx ->
            val principal = config.identityDirectory.resolve(payload.teamId, payload.event.user)
                ?: return@event ctx.ack()
            SlackFileIngress.from(
                event = payload.event,
                principal = principal,
                maxFileSizeBytes = config.maxFileSizeBytes,
            ).forEach { upload ->
                executor.submit {
                    runBlocking { workflow.process(upload) }
                }
            }
            ctx.ack()
        }
    }

    private fun registerConversation(app: App, service: SlackConversationService) {
        app.event(MessageEvent::class.java) { payload, ctx ->
            directMessage(payload.teamId, payload.event)?.let(service::submit)
            ctx.ack()
        }
    }

    private fun directMessage(teamId: String?, event: MessageEvent): SlackConversationMessage? {
        val resolvedTeamId = teamId?.takeIf { it.isNotBlank() } ?: return null
        if (event.channelType != "im") return null
        if (!event.botId.isNullOrBlank()) return null
        if (!event.subtype.isNullOrBlank()) return null
        val userId = event.user?.takeIf { it.isNotBlank() } ?: return null
        val channelId = event.channel?.takeIf { it.isNotBlank() } ?: return null
        val messageTs = event.ts?.takeIf { it.isNotBlank() } ?: return null
        val text = event.text?.takeIf { it.isNotBlank() } ?: return null
        return SlackConversationMessage(
            teamId = resolvedTeamId,
            slackUserId = userId,
            channelId = channelId,
            messageTs = messageTs,
            text = text,
        )
    }
}

package com.homeassistant.adapter.inbound.slack

import com.slack.api.bolt.App
import com.slack.api.model.event.MessageEvent

internal class SlackListeners(
    private val conversationService: SlackConversationService?,
) {
    fun register(app: App) {
        conversationService?.let {
            registerConversation(app, it)
            registerMemberRegistration(app, it)
        }
    }

    private fun registerMemberRegistration(app: App, service: SlackConversationService) {
        app.blockAction(SlackConversationService.REGISTER_ACTION_ID) { request, context ->
            val payload = request.payload
            val teamId = payload.team?.id
            val slackUserId = payload.user?.id
            val channelId = payload.channel?.id
            val triggerId = payload.triggerId
            if (!teamId.isNullOrBlank() && !slackUserId.isNullOrBlank() &&
                !channelId.isNullOrBlank() && !triggerId.isNullOrBlank()
            ) {
                runCatching {
                    service.openRegistrationModal(teamId, slackUserId, channelId, triggerId)
                }
            }
            context.ack()
        }

        app.viewSubmission(SlackConversationService.REGISTER_VIEW_CALLBACK_ID) { request, context ->
            val payload = request.payload
            val displayName = payload.view?.state?.values
                ?.get(SlackConversationService.DISPLAY_NAME_BLOCK_ID)
                ?.get(SlackConversationService.DISPLAY_NAME_ACTION_ID)
                ?.value
                .orEmpty()
                .trim()
            if (displayName.isEmpty() || displayName.length > SlackConversationService.MAX_DISPLAY_NAME_LENGTH) {
                return@viewSubmission context.ackWithErrors(
                    mapOf(SlackConversationService.DISPLAY_NAME_BLOCK_ID to "이름을 1~50자로 입력해주세요."),
                )
            }

            val accepted = service.submitRegistration(
                teamId = payload.team?.id.orEmpty(),
                slackUserId = payload.user?.id.orEmpty(),
                channelId = payload.view?.privateMetadata.orEmpty(),
                displayName = displayName,
            )
            if (!accepted) {
                context.ackWithErrors(
                    mapOf(SlackConversationService.DISPLAY_NAME_BLOCK_ID to "등록 요청을 확인할 수 없습니다."),
                )
            } else {
                context.ack()
            }
        }
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

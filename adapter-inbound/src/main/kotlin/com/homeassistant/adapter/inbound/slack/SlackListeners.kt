package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.memory.answer.MemoryAnswerWorkflow
import com.slack.api.bolt.App
import com.slack.api.model.event.MessageEvent

internal class SlackListeners(
    private val memoryAnswerAdapter: SlackMemoryAnswerAdapter?,
    private val slashCommands: SlackSlashCommandRegistry,
) {
    fun register(app: App) {
        slashCommands.register(app)
        memoryAnswerAdapter?.let {
            registerDirectMessages(app, it)
            registerUserRegistration(app, it)
        }
    }

    private fun registerUserRegistration(app: App, adapter: SlackMemoryAnswerAdapter) {
        app.blockAction(SlackMemoryAnswerAdapter.REGISTER_ACTION_ID) { request, context ->
            val payload = request.payload
            val teamId = payload.team?.id
            val slackUserId = payload.user?.id
            val triggerId = payload.triggerId
            if (!teamId.isNullOrBlank() && !slackUserId.isNullOrBlank() &&
                !triggerId.isNullOrBlank()
            ) {
                runCatching {
                    adapter.openRegistrationModal(teamId, slackUserId, triggerId)
                }
            }
            context.ack()
        }

        app.viewSubmission(SlackMemoryAnswerAdapter.REGISTER_VIEW_CALLBACK_ID) { request, context ->
            val payload = request.payload
            val displayName = payload.view?.state?.values
                ?.get(SlackMemoryAnswerAdapter.DISPLAY_NAME_BLOCK_ID)
                ?.get(SlackMemoryAnswerAdapter.DISPLAY_NAME_ACTION_ID)
                ?.value
                .orEmpty()
                .trim()
            if (displayName.isEmpty() || displayName.length > MemoryAnswerWorkflow.MAX_DISPLAY_NAME_LENGTH) {
                return@viewSubmission context.ackWithErrors(
                    mapOf(SlackMemoryAnswerAdapter.DISPLAY_NAME_BLOCK_ID to "이름을 1~50자로 입력해주세요."),
                )
            }

            val accepted = adapter.submitRegistration(
                teamId = payload.team?.id.orEmpty(),
                slackUserId = payload.user?.id.orEmpty(),
                channelId = payload.view?.privateMetadata.orEmpty(),
                displayName = displayName,
            )
            if (!accepted) {
                context.ackWithErrors(
                    mapOf(SlackMemoryAnswerAdapter.DISPLAY_NAME_BLOCK_ID to "등록 요청을 확인할 수 없습니다."),
                )
            } else {
                context.ack()
            }
        }
    }

    private fun registerDirectMessages(app: App, adapter: SlackMemoryAnswerAdapter) {
        app.event(MessageEvent::class.java) { payload, ctx ->
            directMessage(payload.teamId, payload.event)?.let(adapter::submit)
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

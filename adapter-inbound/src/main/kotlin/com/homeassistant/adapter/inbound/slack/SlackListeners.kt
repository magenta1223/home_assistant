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
    private val confirmationHandlers: SlackConfirmationHandlers,
    private val reviewContexts: SlackReviewContextStore,
    private val slackClient: SlackClient,
    private val executor: ExecutorService,
    private val conversationService: SlackConversationService?,
) {
    fun register(app: App) {
        registerKakaoImport(app)
        registerReview(app)
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

    private fun registerReview(app: App) {
        app.blockAction(SlackTopicBlocks.ACTION_OPEN_REVIEW) { req, ctx ->
            val payload = req.payload
            val reviewId = payload.actions.firstOrNull()?.value
            val userId = payload.user?.id
            if (reviewId.isNullOrBlank() || userId.isNullOrBlank()) return@blockAction ctx.ack()
            val principal = config.identityDirectory.resolve(payload.team?.id, userId)
                ?: return@blockAction ctx.ack()

            when (val result = confirmationHandlers.buildReviewModal(reviewId, principal)) {
                is SlackReviewActionResult.OpenModal -> slackClient.openModal(payload.triggerId, result.view)
                is SlackReviewActionResult.Ephemeral -> slackClient.postEphemeral(
                    channelId = payload.channel.id,
                    userId = userId,
                    text = result.message,
                )
            }
            ctx.ack()
        }

        app.viewSubmission(SlackTopicBlocks.CALLBACK_CONFIRM_TOPICS) { req, ctx ->
            val payload = req.payload
            val reviewId = payload.view.privateMetadata
            val userId = payload.user?.id
            if (reviewId.isNullOrBlank() || userId.isNullOrBlank()) return@viewSubmission ctx.ack()
            val principal = config.identityDirectory.resolve(payload.team?.id, userId)
                ?: return@viewSubmission ctx.ack()
            val context = reviewContexts.find(reviewId)
            val selectedIndices = selectedTopicIndices(payload.view.state?.values.orEmpty())

            executor.submit {
                runBlocking {
                    deliverReviewResult(
                        confirmationHandlers.submitSelection(reviewId, selectedIndices, principal),
                        context,
                        userId,
                    )
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

    private fun deliverReviewResult(
        result: SlackReviewSubmitResult,
        context: SlackReviewContext?,
        userId: String,
    ) {
        val channelId = context?.channelId?.takeIf(String::isNotBlank) ?: return
        val message = when (result) {
            is SlackReviewSubmitResult.Saved -> "선택한 후보 ${result.savedTopicCount}개를 승인했습니다."
            is SlackReviewSubmitResult.Rejected -> result.message
        }
        slackClient.postEphemeral(channelId, userId, message)
    }

    private fun selectedTopicIndices(
        values: Map<String, Map<String, com.slack.api.model.view.ViewState.Value>>,
    ): Set<Int> =
        values.values
            .flatMap(Map<String, com.slack.api.model.view.ViewState.Value>::values)
            .firstOrNull { value -> value.type == "multi_static_select" }
            ?.selectedOptions
            .orEmpty()
            .mapNotNull { option -> option.value?.toIntOrNull() }
            .toSet()

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

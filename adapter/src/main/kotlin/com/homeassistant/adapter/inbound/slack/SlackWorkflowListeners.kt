package com.homeassistant.adapter.inbound.slack

import com.slack.api.bolt.App
import com.slack.api.model.event.MessageFileShareEvent
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService

internal class SlackKakaoListeners(
    private val config: SlackConfig,
    private val workflow: SlackKakaoWorkflow,
    private val executor: ExecutorService,
) : SlackListenerRegistrar {
    override fun register(app: App) {
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
}

internal class SlackConfirmationListeners(
    private val config: SlackConfig,
    private val handlers: SlackConfirmationHandler,
    private val reviewContexts: SlackReviewContextStore,
    private val slackClient: SlackInteractionClient,
    private val executor: ExecutorService,
) : SlackListenerRegistrar {
    override fun register(app: App) {
        registerOpenReview(app)
        registerTopicConfirmation(app)
    }

    private fun registerOpenReview(app: App) {
        app.blockAction(SlackTopicBlocks.ACTION_OPEN_REVIEW) { req, ctx ->
            val payload = req.payload
            val previewId = payload.actions.firstOrNull()?.value
            val userId = payload.user?.id
            if (previewId.isNullOrBlank() || userId.isNullOrBlank()) return@blockAction ctx.ack()
            val principal = config.identityDirectory.resolve(payload.team?.id, userId)
                ?: return@blockAction ctx.ack()

            when (val result = handlers.buildReviewModal(previewId, principal)) {
                is SlackReviewActionResult.OpenModal -> slackClient.openModal(payload.triggerId, result.view)
                is SlackReviewActionResult.Ephemeral -> slackClient.postEphemeral(
                    channelId = payload.channel.id,
                    userId = userId,
                    text = result.message,
                )
            }
            ctx.ack()
        }
    }

    private fun registerTopicConfirmation(app: App) {
        app.viewSubmission(SlackTopicBlocks.CALLBACK_CONFIRM_TOPICS) { req, ctx ->
            val payload = req.payload
            val previewId = payload.view.privateMetadata
            val userId = payload.user?.id
            if (previewId.isNullOrBlank() || userId.isNullOrBlank()) return@viewSubmission ctx.ack()
            val principal = config.identityDirectory.resolve(payload.team?.id, userId)
                ?: return@viewSubmission ctx.ack()
            val context = reviewContexts.find(previewId)
            val selectedIndices = selectedTopicIndices(payload.view.state?.values.orEmpty())

            executor.submit {
                runBlocking {
                    deliverResult(
                        handlers.submitSelection(previewId, selectedIndices, principal),
                        context,
                        userId,
                    )
                }
            }
            ctx.ack()
        }
    }

    private fun deliverResult(
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
}

package com.homeassistant.app.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.MessageFileShareEvent
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SlackSocketRuntime(
    private val config: SlackConfig,
    private val workflow: SlackKakaoAnalysisWorkflow,
    private val confirmationHandlers: SlackConfirmationHandlers,
    private val reviewSessions: SlackTopicReviewSessionStore,
    private val slackClient: SlackClient,
    private val executor: ExecutorService = Executors.newFixedThreadPool(2),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val socketModeApp: SocketModeApp

    init {
        val app = App(
            AppConfig.builder()
                .singleTeamBotToken(config.botToken)
                .build(),
        )

        app.event(MessageFileShareEvent::class.java) { payload, ctx ->
            val uploads = SlackFileIngress.from(payload.event, config.maxFileSizeBytes)
            uploads.forEach { upload ->
                executor.submit {
                    runBlocking {
                        workflow.process(upload)
                    }
                }
            }
            ctx.ack()
        }

        app.blockAction(SlackTopicBlocks.ACTION_OPEN_REVIEW) { req, ctx ->
            val payload = req.payload
            val previewId = payload.actions.firstOrNull()?.value
            val userId = payload.user?.id
            if (previewId.isNullOrBlank() || userId.isNullOrBlank()) return@blockAction ctx.ack()

            when (val result = confirmationHandlers.buildReviewModal(previewId, userId)) {
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
            val previewId = payload.view.privateMetadata
            val userId = payload.user?.id
            if (previewId.isNullOrBlank() || userId.isNullOrBlank()) return@viewSubmission ctx.ack()

            val selectedIndices = selectedTopicIndices(payload.view.state?.values.orEmpty())
            val session = reviewSessions.find(previewId)
            executor.submit {
                runBlocking {
                    when (val result = confirmationHandlers.submitSelection(previewId, selectedIndices, userId)) {
                        is SlackReviewSubmitResult.Saved -> {
                            if (session != null && session.channelId.isNotBlank()) {
                                slackClient.postEphemeral(
                                    channelId = session.channelId,
                                    userId = userId,
                                    text = "선택한 후보 ${result.savedTopicCount}개를 승인했습니다.",
                                )
                            }
                        }
                        is SlackReviewSubmitResult.Rejected -> {
                            if (session != null && session.channelId.isNotBlank()) {
                                slackClient.postEphemeral(
                                    channelId = session.channelId,
                                    userId = userId,
                                    text = result.message,
                                )
                            }
                        }
                    }
                }
            }
            ctx.ack()
        }

        socketModeApp = SocketModeApp(config.appToken, app)
    }

    fun startAsync() {
        socketModeApp.startAsync()
        log.info("Slack Socket Mode runtime started")
    }

    override fun close() {
        runCatching { socketModeApp.close() }
            .onFailure { log.warn("Failed to close Slack Socket Mode runtime: ${it.message}") }
        executor.shutdown()
    }

    private fun selectedTopicIndices(
        values: Map<String, Map<String, com.slack.api.model.view.ViewState.Value>>,
    ): Set<Int> =
        values.values
            .flatMap { block -> block.values }
            .firstOrNull { value -> value.type == "multi_static_select" }
            ?.selectedOptions
            .orEmpty()
            .mapNotNull { option -> option.value?.toIntOrNull() }
            .toSet()
}

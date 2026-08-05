package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.slackconversation.handle.ConversationAnswerPublisher
import com.homeassistant.application.slackconversation.handle.HandleSlackConversation
import com.homeassistant.application.slackconversation.handle.SlackConversationMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

interface SlackConversationHandler {
    fun submit(message: SlackConversationMessage)
}

internal class SlackConversationService(
    private val handleConversation: HandleSlackConversation,
    private val executor: Executor = Executors.newCachedThreadPool(),
) : SlackConversationHandler {
    private val queues = ConcurrentHashMap<SlackActorKey, SerialTaskQueue>()

    override fun submit(message: SlackConversationMessage) {
        val actor = SlackActorKey(message.teamId, message.slackUserId)
        queues.computeIfAbsent(actor) { SerialTaskQueueFactory.create(executor) }
            .execute { handle(message) }
    }

    internal fun handle(message: SlackConversationMessage) {
        handleConversation.execute(message)
    }
}

internal class SlackConversationAnswerPublisher(
    private val slack: SlackMessageClient,
) : ConversationAnswerPublisher {
    override fun postAnswer(channelId: String, answer: String): String =
        slack.postMessage(
            channelId = channelId,
            text = answer,
            blocks = emptyList(),
        ).responseTs

    override fun postRetryableError(channelId: String) {
        runCatching {
            slack.postMessage(
                channelId = channelId,
                text = "답변 처리에 실패했습니다. 새 메시지로 다시 시도해주세요.",
                blocks = emptyList(),
            )
        }
    }
}

private data class SlackActorKey(
    val teamId: String,
    val slackUserId: String,
)

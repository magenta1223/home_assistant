package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.output.slackconversation.ConversationAnswerPublisher
import com.homeassistant.application.port.input.slackconversation.SlackConversationHandler
import com.homeassistant.application.port.input.slackconversation.SlackConversationMessage
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal class SlackConversationService(
    private val handleConversation: SlackConversationHandler,
    private val executor: Executor = Executors.newCachedThreadPool(),
) {
    private val queues = ConcurrentHashMap<SlackActorKey, SerialTaskQueue>()

    fun submit(message: SlackConversationMessage) {
        val actor = SlackActorKey(message.teamId, message.slackUserId)
        queues.computeIfAbsent(actor) { SerialTaskQueue(executor) }
            .execute { handle(message) }
    }

    internal fun handle(message: SlackConversationMessage) {
        handleConversation.execute(message)
    }
}

class SlackConversationAnswerPublisher(
    private val slack: SlackClient,
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

private class SerialTaskQueue(
    private val executor: Executor,
) {
    private val tasks = ArrayDeque<Runnable>()
    private var running = false

    fun execute(task: () -> Unit) {
        val shouldSchedule = synchronized(this) {
            tasks.addLast(Runnable(task))
            if (running) false else {
                running = true
                true
            }
        }
        if (shouldSchedule) scheduleNext()
    }

    private fun scheduleNext() {
        val task = synchronized(this) { tasks.pollFirst() }
        if (task == null) {
            synchronized(this) { running = false }
            return
        }
        executor.execute {
            try {
                task.run()
            } finally {
                scheduleNext()
            }
        }
    }
}

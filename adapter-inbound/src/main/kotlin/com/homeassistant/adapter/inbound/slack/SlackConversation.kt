package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal class SlackConversationService(
    private val identityDirectory: SlackIdentityDirectory,
    private val memoryConversation: MemoryConversation,
    private val slack: SlackClient,
    private val executor: Executor = Executors.newCachedThreadPool(),
) {
    private val queues = ConcurrentHashMap<SlackActorKey, SerialTaskQueue>()

    fun submit(message: SlackDirectMessage) {
        val actor = SlackActorKey(message.teamId, message.slackUserId)
        queues.computeIfAbsent(actor) { SerialTaskQueue(executor) }
            .execute { handle(message) }
    }

    internal fun handle(message: SlackDirectMessage) {
        val userId = identityDirectory.resolve(message.teamId, message.slackUserId) ?: return
        val key = MemoryConversationRequestKey(message.channelId, message.messageTs)
        when (
            val result = memoryConversation.answer(
                MemoryConversationRequest(
                    participant = MemoryConversationParticipant(
                        scopeId = message.teamId,
                        participantId = message.slackUserId,
                        userId = userId,
                    ),
                    key = key,
                    question = message.text,
                ),
            )
        ) {
            is MemoryConversationResult.AnswerReady -> {
                runCatching {
                    slack.postMessage(message.channelId, result.answer, emptyList())
                }.onSuccess { delivery ->
                    memoryConversation.markDelivered(key, delivery.responseTs)
                }
            }
            MemoryConversationResult.Failed -> postRetryableError(message.channelId)
            MemoryConversationResult.AlreadyHandled -> Unit
        }
    }

    private fun postRetryableError(channelId: String) {
        runCatching {
            slack.postMessage(
                channelId = channelId,
                text = "답변 처리에 실패했습니다. 새 메시지로 다시 시도해주세요.",
                blocks = emptyList(),
            )
        }
    }
}

internal data class SlackDirectMessage(
    val teamId: String,
    val slackUserId: String,
    val channelId: String,
    val messageTs: String,
    val text: String,
)

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

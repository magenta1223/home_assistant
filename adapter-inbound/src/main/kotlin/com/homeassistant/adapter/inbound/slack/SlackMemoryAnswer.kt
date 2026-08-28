package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.memory.answer.CompleteUserRegistrationRequest
import com.homeassistant.application.port.input.memory.answer.ConversationRequestKey
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerWorkflow
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationStartResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationValidationResult
import com.homeassistant.application.port.input.identity.ConversationIdentity
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal class SlackMemoryAnswerAdapter(
    private val configuredTeamId: String,
    private val memoryAnswerWorkflow: MemoryAnswerWorkflow,
    private val slack: SlackClient,
    private val executor: Executor = Executors.newCachedThreadPool(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val queues = ConcurrentHashMap<SlackActorKey, SerialTaskQueue>()

    fun submit(message: SlackDirectMessage) {
        val actor = SlackActorKey(message.teamId, message.slackUserId)
        queues.computeIfAbsent(actor) { SerialTaskQueue(executor) }
            .execute { handle(message) }
    }

    internal fun handle(message: SlackDirectMessage) {
        if (message.teamId != configuredTeamId) return
        val request = message.toApplicationRequest()
        val workflowStartedAt = System.nanoTime()
        val result = memoryAnswerWorkflow.receive(request)
        log.info(
            "Latency stage=slack-memory-workflow elapsedMs={} result={}",
            elapsedMillis(workflowStartedAt),
            result.javaClass.simpleName,
        )
        render(request.key, result, request.identity)
    }

    internal fun openRegistrationModal(
        teamId: String,
        slackUserId: String,
        triggerId: String,
    ) {
        if (teamId != configuredTeamId) return
        when (val result = memoryAnswerWorkflow.beginRegistration(ConversationIdentity(teamId, slackUserId))) {
            is UserRegistrationStartResult.Ready ->
                slack.openModal(triggerId, registrationModal(result.replyKey.streamId))
            UserRegistrationStartResult.AlreadyRegistered,
            UserRegistrationStartResult.NoPendingQuestion,
            UserRegistrationStartResult.Failed,
            -> Unit
        }
    }

    internal fun submitRegistration(
        teamId: String,
        slackUserId: String,
        channelId: String,
        displayName: String,
    ): Boolean {
        if (teamId != configuredTeamId || channelId.isBlank()) return false
        val validation = memoryAnswerWorkflow.validateRegistration(displayName)
        if (validation !is UserRegistrationValidationResult.Valid) return false

        val actor = SlackActorKey(teamId, slackUserId)
        queues.computeIfAbsent(actor) { SerialTaskQueue(executor) }
            .execute { completeRegistration(actor, channelId, validation.displayName) }
        return true
    }

    private fun completeRegistration(actor: SlackActorKey, channelId: String, displayName: String) {
        when (
            val result = memoryAnswerWorkflow.completeRegistration(
                CompleteUserRegistrationRequest(
                    identity = ConversationIdentity(actor.teamId, actor.slackUserId),
                    displayName = displayName,
                ),
            ) { registration ->
                runCatching {
                    slack.postMessage(
                        channelId = registration.replyKey.streamId,
                        text = "${registration.displayName}님, 등록되었습니다.",
                        blocks = emptyList(),
                    )
                }
            }
        ) {
            is UserRegistrationResult.Completed -> render(result.replyKey, result.conversation)
            UserRegistrationResult.InvalidDisplayName,
            UserRegistrationResult.NoPendingQuestion,
            UserRegistrationResult.Failed,
            -> postRegistrationError(channelId)
        }
    }

    private fun render(
        key: ConversationRequestKey,
        result: MemoryAnswerResult,
        registrationIdentity: ConversationIdentity? = null,
    ) {
        when (result) {
            MemoryAnswerResult.RegistrationRequired -> runCatching {
                slack.postMessage(
                    channelId = key.streamId,
                    text = "처음 오셨네요. 답변을 받으려면 사용자 등록을 완료해주세요.",
                    blocks = registrationPromptBlocks(),
                )
            }.onFailure {
                registrationIdentity?.let { identity ->
                    memoryAnswerWorkflow.registrationPromptDeliveryFailed(identity, key)
                }
            }
            MemoryAnswerResult.RegistrationPending,
            MemoryAnswerResult.AlreadyHandled,
            -> Unit
            is MemoryAnswerResult.AnswerReady -> {
                val deliveryStartedAt = System.nanoTime()
                runCatching {
                    slack.postMessage(key.streamId, result.answer, emptyList())
                }.onSuccess { delivery ->
                    memoryAnswerWorkflow.markDelivered(key, delivery.responseTs)
                    log.info(
                        "Latency stage=slack-answer-delivery result=success elapsedMs={}",
                        elapsedMillis(deliveryStartedAt),
                    )
                }.onFailure { error ->
                    log.warn(
                        "Latency stage=slack-answer-delivery result=failure category={} elapsedMs={}",
                        error.javaClass.simpleName,
                        elapsedMillis(deliveryStartedAt),
                    )
                }
            }
            MemoryAnswerResult.Unavailable,
            MemoryAnswerResult.Failed,
            -> postRetryableError(key.streamId)
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

    private fun postRegistrationError(channelId: String) {
        runCatching {
            slack.postMessage(
                channelId = channelId,
                text = "사용자 등록에 실패했습니다. 다시 시도해주세요.",
                blocks = emptyList(),
            )
        }
    }

    private fun SlackDirectMessage.toApplicationRequest(): MemoryAnswerRequest =
        MemoryAnswerRequest(
            identity = ConversationIdentity(teamId, slackUserId),
            key = ConversationRequestKey(channelId, messageTs),
            question = text,
        )

    private fun elapsedMillis(startedAt: Long): Long =
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

    private fun registrationPromptBlocks(): List<Map<String, Any>> = listOf(
        mapOf(
            "type" to "section",
            "text" to mapOf(
                "type" to "mrkdwn",
                "text" to "이름을 등록하면 나에게 허용된 메모리를 바탕으로 답변할 수 있어요.",
            ),
        ),
        mapOf(
            "type" to "actions",
            "elements" to listOf(
                mapOf(
                    "type" to "button",
                    "action_id" to REGISTER_ACTION_ID,
                    "text" to mapOf("type" to "plain_text", "text" to "등록하기"),
                    "style" to "primary",
                    "value" to "register",
                ),
            ),
        ),
    )

    private fun registrationModal(channelId: String): Map<String, Any> = mapOf(
        "type" to "modal",
        "callback_id" to REGISTER_VIEW_CALLBACK_ID,
        "private_metadata" to channelId,
        "title" to mapOf("type" to "plain_text", "text" to "사용자 등록"),
        "submit" to mapOf("type" to "plain_text", "text" to "등록"),
        "close" to mapOf("type" to "plain_text", "text" to "취소"),
        "blocks" to listOf(
            mapOf(
                "type" to "input",
                "block_id" to DISPLAY_NAME_BLOCK_ID,
                "label" to mapOf("type" to "plain_text", "text" to "이름"),
                "element" to mapOf(
                    "type" to "plain_text_input",
                    "action_id" to DISPLAY_NAME_ACTION_ID,
                    "max_length" to MemoryAnswerWorkflow.MAX_DISPLAY_NAME_LENGTH,
                    "placeholder" to mapOf("type" to "plain_text", "text" to "사용할 이름을 입력하세요"),
                ),
            ),
        ),
    )

    companion object {
        const val REGISTER_ACTION_ID = "register_user"
        const val REGISTER_VIEW_CALLBACK_ID = "register_user"
        const val DISPLAY_NAME_BLOCK_ID = "display_name"
        const val DISPLAY_NAME_ACTION_ID = "display_name_input"
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

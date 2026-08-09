package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.HouseholdMembers
import com.homeassistant.application.port.input.identity.RegisterHouseholdMemberRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import com.homeassistant.domain.identity.UserId
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal class SlackConversationService(
    private val configuredTeamId: String,
    private val householdMembers: HouseholdMembers,
    private val memoryConversation: MemoryConversation?,
    private val slack: SlackClient,
    private val executor: Executor = Executors.newCachedThreadPool(),
) {
    private val queues = ConcurrentHashMap<SlackActorKey, SerialTaskQueue>()
    private val pendingRegistrations = ConcurrentHashMap<SlackActorKey, SlackDirectMessage>()

    fun submit(message: SlackDirectMessage) {
        val actor = SlackActorKey(message.teamId, message.slackUserId)
        queues.computeIfAbsent(actor) { SerialTaskQueue(executor) }
            .execute { handle(message) }
    }

    internal fun handle(message: SlackDirectMessage) {
        if (message.teamId != configuredTeamId) return
        val identity = message.identity()
        val member = householdMembers.find(identity)
        if (member == null) {
            requestRegistration(message)
            return
        }
        answer(message, member.userId)
    }

    internal fun openRegistrationModal(
        teamId: String,
        slackUserId: String,
        channelId: String,
        triggerId: String,
    ) {
        if (teamId != configuredTeamId) return
        if (householdMembers.find(ConversationIdentity(teamId, slackUserId)) != null) return
        slack.openModal(triggerId, registrationModal(channelId))
    }

    internal fun submitRegistration(
        teamId: String,
        slackUserId: String,
        channelId: String,
        displayName: String,
    ): Boolean {
        val normalizedName = displayName.trim()
        if (teamId != configuredTeamId || channelId.isBlank()) return false
        if (normalizedName.isEmpty() || normalizedName.length > MAX_DISPLAY_NAME_LENGTH) return false

        val actor = SlackActorKey(teamId, slackUserId)
        queues.computeIfAbsent(actor) { SerialTaskQueue(executor) }
            .execute { completeRegistration(actor, channelId, normalizedName) }
        return true
    }

    private fun completeRegistration(actor: SlackActorKey, channelId: String, displayName: String) {
        val member = runCatching {
            householdMembers.register(
                RegisterHouseholdMemberRequest(
                    identity = ConversationIdentity(actor.teamId, actor.slackUserId),
                    displayName = displayName,
                ),
            )
        }.getOrElse {
            postRegistrationError(channelId)
            return
        }
        val pendingMessage = pendingRegistrations.remove(actor)
        runCatching {
            slack.postMessage(
                channelId = channelId,
                text = "${member.displayName}님, 등록되었습니다.",
                blocks = emptyList(),
            )
        }
        if (pendingMessage != null) answer(pendingMessage, member.userId)
    }

    private fun requestRegistration(message: SlackDirectMessage) {
        val actor = SlackActorKey(message.teamId, message.slackUserId)
        if (pendingRegistrations.putIfAbsent(actor, message) != null) return
        runCatching {
            slack.postMessage(
                channelId = message.channelId,
                text = "처음 오셨네요. 답변을 받으려면 사용자 등록을 완료해주세요.",
                blocks = registrationPromptBlocks(),
            )
        }.onFailure {
            pendingRegistrations.remove(actor, message)
        }
    }

    private fun answer(message: SlackDirectMessage, userId: UserId) {
        val conversation = memoryConversation
        if (conversation == null) {
            postRetryableError(message.channelId)
            return
        }
        val key = MemoryConversationRequestKey(message.channelId, message.messageTs)
        when (
            val result = conversation.answer(
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
                    conversation.markDelivered(key, delivery.responseTs)
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

    private fun postRegistrationError(channelId: String) {
        runCatching {
            slack.postMessage(
                channelId = channelId,
                text = "사용자 등록에 실패했습니다. 다시 시도해주세요.",
                blocks = emptyList(),
            )
        }
    }

    private fun SlackDirectMessage.identity(): ConversationIdentity =
        ConversationIdentity(teamId, slackUserId)

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
                    "max_length" to MAX_DISPLAY_NAME_LENGTH,
                    "placeholder" to mapOf("type" to "plain_text", "text" to "사용할 이름을 입력하세요"),
                ),
            ),
        ),
    )

    companion object {
        const val REGISTER_ACTION_ID = "register_household_member"
        const val REGISTER_VIEW_CALLBACK_ID = "register_household_member"
        const val DISPLAY_NAME_BLOCK_ID = "display_name"
        const val DISPLAY_NAME_ACTION_ID = "display_name_input"
        const val MAX_DISPLAY_NAME_LENGTH = 50
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

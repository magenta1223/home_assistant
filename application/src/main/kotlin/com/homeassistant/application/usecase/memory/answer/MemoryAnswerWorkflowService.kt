package com.homeassistant.application.usecase.memory.answer

import com.homeassistant.application.port.input.memory.answer.CompleteUserRegistrationRequest
import com.homeassistant.application.port.input.memory.answer.ConversationRequestKey
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerWorkflow
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationNotice
import com.homeassistant.application.port.input.memory.answer.UserRegistrationResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationStartResult
import com.homeassistant.application.port.input.memory.answer.UserRegistrationValidationResult
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.identity.RegisterUserRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
import com.homeassistant.application.port.output.memory.answer.PendingRegistrationQuestionStore
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import org.slf4j.LoggerFactory
import java.time.Clock

class MemoryAnswerWorkflowService(
    private val users: UserRegistry,
    private val pendingQuestions: PendingRegistrationQuestionStore,
    private val memoryConversation: MemoryConversation?,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryAnswerWorkflow {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun receive(request: MemoryAnswerRequest): MemoryAnswerResult {
        val user = try {
            users.find(request.identity)
        } catch (error: Exception) {
            log.warn("User resolution failed category={}", error.javaClass.simpleName)
            return MemoryAnswerResult.Failed
        }
        if (user == null) {
            return try {
                if (pendingQuestions.rememberFirst(request, clock.millis())) {
                    MemoryAnswerResult.RegistrationRequired
                } else {
                    MemoryAnswerResult.RegistrationPending
                }
            } catch (error: Exception) {
                log.warn("Pending registration storage failed category={}", error.javaClass.simpleName)
                MemoryAnswerResult.Failed
            }
        }
        return answer(request, user.userId)
    }

    override fun beginRegistration(identity: ConversationIdentity): UserRegistrationStartResult = try {
        if (users.find(identity) != null) {
            UserRegistrationStartResult.AlreadyRegistered
        } else {
            pendingQuestions.find(identity)
                ?.let { UserRegistrationStartResult.Ready(it.key) }
                ?: UserRegistrationStartResult.NoPendingQuestion
        }
    } catch (error: Exception) {
        log.warn("Registration start failed category={}", error.javaClass.simpleName)
        UserRegistrationStartResult.Failed
    }

    override fun validateRegistration(displayName: String): UserRegistrationValidationResult =
        try {
            UserRegistrationValidationResult.Valid(RegisteredUser.normalizeDisplayName(displayName))
        } catch (_: IllegalArgumentException) {
            UserRegistrationValidationResult.Invalid
        }

    override fun completeRegistration(
        request: CompleteUserRegistrationRequest,
        onRegistered: (UserRegistrationNotice) -> Unit,
    ): UserRegistrationResult {
        val displayName = when (val validation = validateRegistration(request.displayName)) {
            UserRegistrationValidationResult.Invalid -> return UserRegistrationResult.InvalidDisplayName
            is UserRegistrationValidationResult.Valid -> validation.displayName
        }
        val pending = try {
            pendingQuestions.find(request.identity)
        } catch (error: Exception) {
            log.warn("Pending registration lookup failed category={}", error.javaClass.simpleName)
            return UserRegistrationResult.Failed
        } ?: return UserRegistrationResult.NoPendingQuestion

        val user = try {
            users.register(RegisterUserRequest(request.identity, displayName))
        } catch (error: IllegalArgumentException) {
            return UserRegistrationResult.InvalidDisplayName
        } catch (error: Exception) {
            log.warn("User registration failed category={}", error.javaClass.simpleName)
            return UserRegistrationResult.Failed
        }

        runCatching {
            onRegistered(UserRegistrationNotice(user.displayName, pending.key))
        }.onFailure { error ->
            log.warn("Registration notice failed category={}", error.javaClass.simpleName)
        }
        val result = answer(pending, user.userId)
        try {
            pendingQuestions.remove(request.identity)
        } catch (error: Exception) {
            log.warn("Completed pending registration cleanup failed category={}", error.javaClass.simpleName)
        }
        return UserRegistrationResult.Completed(pending.key, result)
    }

    override fun markDelivered(key: ConversationRequestKey, deliveryId: String) {
        memoryConversation?.markDelivered(key, deliveryId)
    }

    override fun registrationPromptDeliveryFailed(identity: ConversationIdentity, key: ConversationRequestKey) {
        try {
            if (pendingQuestions.find(identity)?.key == key) {
                pendingQuestions.remove(identity)
            }
        } catch (error: Exception) {
            log.warn("Pending registration delivery recovery failed category={}", error.javaClass.simpleName)
        }
    }

    private fun answer(
        request: MemoryAnswerRequest,
        userId: UserId,
    ): MemoryAnswerResult {
        val conversation = memoryConversation ?: return MemoryAnswerResult.Unavailable
        return when (
            val result = conversation.answer(
                MemoryConversationRequest(
                    participant = MemoryConversationParticipant(
                        scopeId = request.identity.scopeId,
                        participantId = request.identity.participantId,
                        userId = userId,
                    ),
                    key = request.key,
                    question = request.question,
                ),
            )
        ) {
            is MemoryConversationResult.AnswerReady -> MemoryAnswerResult.AnswerReady(result.answer)
            MemoryConversationResult.AlreadyHandled -> MemoryAnswerResult.AlreadyHandled
            MemoryConversationResult.Failed -> MemoryAnswerResult.Failed
        }
    }
}

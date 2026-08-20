package com.homeassistant.application.port.input.memory.answer

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.domain.identity.RegisteredUser

data class ConversationRequestKey(
    val streamId: String,
    val requestId: String,
) {
    init {
        require(streamId.isNotBlank()) { "streamId is required" }
        require(requestId.isNotBlank()) { "requestId is required" }
    }
}

data class MemoryAnswerRequest(
    val identity: ConversationIdentity,
    val key: ConversationRequestKey,
    val question: String,
) {
    init {
        require(question.isNotBlank()) { "question is required" }
    }
}

sealed interface MemoryAnswerResult {
    data object RegistrationRequired : MemoryAnswerResult
    data object RegistrationPending : MemoryAnswerResult
    data class AnswerReady(val answer: String) : MemoryAnswerResult
    data object AlreadyHandled : MemoryAnswerResult
    data object Unavailable : MemoryAnswerResult
    data object Failed : MemoryAnswerResult
}

sealed interface UserRegistrationStartResult {
    data class Ready(val replyKey: ConversationRequestKey) : UserRegistrationStartResult
    data object AlreadyRegistered : UserRegistrationStartResult
    data object NoPendingQuestion : UserRegistrationStartResult
    data object Failed : UserRegistrationStartResult
}

sealed interface UserRegistrationValidationResult {
    data class Valid(val displayName: String) : UserRegistrationValidationResult
    data object Invalid : UserRegistrationValidationResult
}

data class CompleteUserRegistrationRequest(
    val identity: ConversationIdentity,
    val displayName: String,
)

data class UserRegistrationNotice(
    val displayName: String,
    val replyKey: ConversationRequestKey,
)

sealed interface UserRegistrationResult {
    data class Completed(
        val replyKey: ConversationRequestKey,
        val conversation: MemoryAnswerResult,
    ) : UserRegistrationResult

    data object InvalidDisplayName : UserRegistrationResult
    data object NoPendingQuestion : UserRegistrationResult
    data object Failed : UserRegistrationResult
}

/** Coordinates user registration, memory-backed answers, and channel delivery state. */
interface MemoryAnswerWorkflow {
    fun receive(request: MemoryAnswerRequest): MemoryAnswerResult

    fun beginRegistration(identity: ConversationIdentity): UserRegistrationStartResult

    fun validateRegistration(displayName: String): UserRegistrationValidationResult

    fun completeRegistration(
        request: CompleteUserRegistrationRequest,
        onRegistered: (UserRegistrationNotice) -> Unit,
    ): UserRegistrationResult

    fun registrationPromptDeliveryFailed(identity: ConversationIdentity, key: ConversationRequestKey)

    fun markDelivered(key: ConversationRequestKey, deliveryId: String)

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = RegisteredUser.MAX_DISPLAY_NAME_LENGTH
    }
}

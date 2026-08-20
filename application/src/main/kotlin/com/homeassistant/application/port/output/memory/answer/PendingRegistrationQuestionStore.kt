package com.homeassistant.application.port.output.memory.answer

import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.identity.ConversationIdentity

/** Persists the first question that must resume after a member completes registration. */
interface PendingRegistrationQuestionStore {
    fun rememberFirst(request: MemoryAnswerRequest, now: Long): Boolean

    fun find(identity: ConversationIdentity): MemoryAnswerRequest?

    fun remove(identity: ConversationIdentity)
}

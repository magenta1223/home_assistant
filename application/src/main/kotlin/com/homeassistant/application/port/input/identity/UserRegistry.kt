package com.homeassistant.application.port.input.identity

import com.homeassistant.domain.identity.RegisteredUser

data class ConversationIdentity(
    val scopeId: String,
    val participantId: String,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId is required" }
        require(participantId.isNotBlank()) { "participantId is required" }
    }
}

data class RegisterUserRequest(
    val identity: ConversationIdentity,
    val displayName: String,
)

/** Resolves and registers application users from authenticated conversation identities. */
interface UserRegistry {
    fun find(identity: ConversationIdentity): RegisteredUser?

    fun register(request: RegisterUserRequest): RegisteredUser

    fun list(): List<RegisteredUser>

    companion object {
        val NONE: UserRegistry = object : UserRegistry {
            override fun find(identity: ConversationIdentity): RegisteredUser? = null

            override fun register(request: RegisterUserRequest): RegisteredUser =
                error("user registration is unavailable")

            override fun list(): List<RegisteredUser> = emptyList()
        }
    }
}

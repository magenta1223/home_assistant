package com.homeassistant.application.port.output.identity

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId

interface UserStore {
    fun find(identity: ConversationIdentity): RegisteredUser?

    /** Atomically creates or completes a member registration for this identity. */
    fun register(
        identity: ConversationIdentity,
        proposedUserId: UserId,
        displayName: String,
        now: Long,
    ): RegisteredUser

    fun list(): List<RegisteredUser>

    fun isRegistered(userId: UserId): Boolean
}

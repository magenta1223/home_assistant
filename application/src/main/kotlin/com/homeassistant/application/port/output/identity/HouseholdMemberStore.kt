package com.homeassistant.application.port.output.identity

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.domain.identity.HouseholdMember
import com.homeassistant.domain.identity.UserId

interface HouseholdMemberStore {
    fun find(identity: ConversationIdentity): HouseholdMember?

    /** Atomically creates or completes a member registration for this identity. */
    fun register(
        identity: ConversationIdentity,
        proposedUserId: UserId,
        displayName: String,
        now: Long,
    ): HouseholdMember

    fun list(): List<HouseholdMember>

    fun isRegistered(userId: UserId): Boolean

    /** Reserves an existing application user for one-time migration from configured mappings. */
    fun reserve(identity: ConversationIdentity, userId: UserId, now: Long)
}

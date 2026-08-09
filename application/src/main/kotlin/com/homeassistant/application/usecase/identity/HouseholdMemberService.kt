package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.HouseholdMembers
import com.homeassistant.application.port.input.identity.RegisterHouseholdMemberRequest
import com.homeassistant.application.port.output.identity.HouseholdMemberStore
import com.homeassistant.domain.identity.HouseholdMember
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import java.util.UUID

class HouseholdMemberService(
    private val store: HouseholdMemberStore,
    private val newUserId: () -> UserId = { UserId("member-${UUID.randomUUID()}") },
    private val clock: () -> Long = System::currentTimeMillis,
) : HouseholdMembers, HouseholdAccessPolicy {
    override fun find(identity: ConversationIdentity): HouseholdMember? = store.find(identity)

    override fun register(request: RegisterHouseholdMemberRequest): HouseholdMember {
        val displayName = request.displayName.trim()
        require(displayName.isNotEmpty()) { "displayName is required" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName must be at most $MAX_DISPLAY_NAME_LENGTH characters"
        }
        return store.register(request.identity, newUserId(), displayName, clock())
    }

    override fun list(): List<HouseholdMember> = store.list()

    override fun isAuthorized(userId: UserId): Boolean = store.isRegistered(userId)

    fun reserveLegacy(identity: ConversationIdentity, userId: UserId) {
        store.reserve(identity, userId, clock())
    }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 50
    }
}

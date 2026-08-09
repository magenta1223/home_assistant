package com.homeassistant.application.port.input.identity

import com.homeassistant.domain.identity.HouseholdMember

data class ConversationIdentity(
    val scopeId: String,
    val participantId: String,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId is required" }
        require(participantId.isNotBlank()) { "participantId is required" }
    }
}

data class RegisterHouseholdMemberRequest(
    val identity: ConversationIdentity,
    val displayName: String,
)

/** Resolves and registers household members from authenticated conversation identities. */
interface HouseholdMembers {
    fun find(identity: ConversationIdentity): HouseholdMember?

    fun register(request: RegisterHouseholdMemberRequest): HouseholdMember

    fun list(): List<HouseholdMember>

    companion object {
        val NONE: HouseholdMembers = object : HouseholdMembers {
            override fun find(identity: ConversationIdentity): HouseholdMember? = null

            override fun register(request: RegisterHouseholdMemberRequest): HouseholdMember =
                error("household member registration is unavailable")

            override fun list(): List<HouseholdMember> = emptyList()
        }
    }
}

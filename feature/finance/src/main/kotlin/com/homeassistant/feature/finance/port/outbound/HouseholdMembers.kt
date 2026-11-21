package com.homeassistant.feature.finance.port.outbound

import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId

/** Tells whose finances a user shares. */
fun interface HouseholdMembers {
    /** The members of the household of [userId], ordered by name; empty when [userId] is in none. */
    fun findMembersOf(userId: UserId): List<RegisteredUser>
}

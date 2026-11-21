package com.homeassistant.application.port.inbound.household

import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.time.Instant

/** Lets people form a household by invitation and see who is in it. */
interface Households {
    /** The members of the household of [userId], ordered by name; empty when [userId] is in none. */
    fun membersOf(userId: UserId): List<RegisteredUser>

    /** A one-time code another person enters to join the household of [userId], formed now when [userId] has none. */
    fun invite(userId: UserId): HouseholdInvitation

    /**
     * Moves [userId] into the household that [code] invites to and returns its members.
     *
     * @throws InvalidInvitationException when [code] is unknown, used or expired
     * @throws AlreadyInHouseholdException when [userId] already shares a household with someone else
     */
    fun join(userId: UserId, code: String): List<RegisteredUser>
}

/** An invitation whose [code] is valid until [expiresAt] and can be used once. */
data class HouseholdInvitation(
    val code: String,
    val expiresAt: Instant,
)

class InvalidInvitationException : RuntimeException("the invitation code is unknown, used or expired")

class AlreadyInHouseholdException : RuntimeException("the user already shares a household with someone else")

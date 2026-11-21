package com.homeassistant.feature.identity.port.outbound

import com.homeassistant.domain.household.HouseholdId
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId

/** Stores who belongs to which household and the invitations to join one. */
interface HouseholdRepository {
    /** The household [userId] belongs to, or null. */
    fun findHouseholdOf(userId: UserId): HouseholdId?

    /** The members of the household [userId] belongs to, ordered by name; empty when [userId] is in none. */
    fun findMembersOf(userId: UserId): List<RegisteredUser>

    /** The invitation whose code hash is [codeHash] and whose expiry is after [now], or null. */
    fun findUnexpiredInvitation(codeHash: String, now: Long): StoredInvitation?

    /**
     * Puts the user of [membership] into its household, forming the household when it is new and taking the user out
     * of the household they were in. Returns [membership].
     */
    fun save(membership: HouseholdMembership): HouseholdMembership

    /** Stores [invitation] and returns it. */
    fun save(invitation: StoredInvitation): StoredInvitation

    /** Removes the invitation whose code hash is [codeHash]; an unknown hash removes nothing. */
    fun deleteInvitationByCodeHash(codeHash: String)
}

/** [userId] has belonged to [householdId] since [joinedAt]. */
data class HouseholdMembership(
    val householdId: HouseholdId,
    val userId: UserId,
    val joinedAt: Long,
)

/** An invitation to [householdId], stored by the hash of its code so a leaked database cannot be used to join. */
data class StoredInvitation(
    val codeHash: String,
    val householdId: HouseholdId,
    val expiresAt: Long,
)

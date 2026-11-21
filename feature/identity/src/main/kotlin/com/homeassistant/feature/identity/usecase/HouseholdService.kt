package com.homeassistant.feature.identity.usecase

import com.homeassistant.application.port.inbound.household.AlreadyInHouseholdException
import com.homeassistant.application.port.inbound.household.HouseholdInvitation
import com.homeassistant.application.port.inbound.household.Households
import com.homeassistant.application.port.inbound.household.InvalidInvitationException
import com.homeassistant.domain.household.HouseholdId
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.identity.port.outbound.HouseholdMembership
import com.homeassistant.feature.identity.port.outbound.HouseholdRepository
import com.homeassistant.feature.identity.port.outbound.StoredInvitation
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Forms households and lets people join them with one-time invitation codes kept in [households]. */
class HouseholdService(
    private val households: HouseholdRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : Households {

    override fun membersOf(userId: UserId): List<RegisteredUser> = households.findMembersOf(userId)

    override fun invite(userId: UserId): HouseholdInvitation {
        val household = households.findHouseholdOf(userId) ?: formHousehold(userId)
        val code = newCode()
        val expiresAt = clock() + INVITATION_LIFETIME.toMillis()
        households.save(StoredInvitation(secretHash(code), household, expiresAt))
        return HouseholdInvitation(code, Instant.ofEpochMilli(expiresAt))
    }

    override fun join(userId: UserId, code: String): List<RegisteredUser> {
        val codeHash = secretHash(code.trim().uppercase())
        val invitation = households.findUnexpiredInvitation(codeHash, clock()) ?: throw InvalidInvitationException()
        if (sharesHouseholdWithOthers(userId)) throw AlreadyInHouseholdException()
        households.save(HouseholdMembership(invitation.householdId, userId, clock()))
        households.deleteInvitationByCodeHash(codeHash)
        return households.findMembersOf(userId)
    }

    private fun formHousehold(founder: UserId): HouseholdId {
        val household = HouseholdId("household-${UUID.randomUUID()}")
        households.save(HouseholdMembership(household, founder, clock()))
        return household
    }

    private fun sharesHouseholdWithOthers(userId: UserId): Boolean = households.findMembersOf(userId).size > 1

    /** A code short enough to type on a phone, without letters that look like digits. */
    private fun newCode(): String {
        val random = SecureRandom()
        return (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    }

    private companion object {
        val INVITATION_LIFETIME: Duration = Duration.ofDays(1)
        const val CODE_LENGTH = 8
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}

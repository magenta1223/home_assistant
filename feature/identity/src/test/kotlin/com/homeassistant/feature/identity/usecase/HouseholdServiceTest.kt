package com.homeassistant.feature.identity.usecase

import com.homeassistant.application.port.inbound.household.AlreadyInHouseholdException
import com.homeassistant.application.port.inbound.household.InvalidInvitationException
import com.homeassistant.domain.household.HouseholdId
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.identity.port.outbound.HouseholdMembership
import com.homeassistant.feature.identity.port.outbound.HouseholdRepository
import com.homeassistant.feature.identity.port.outbound.StoredInvitation
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HouseholdServiceTest {
    private var now = 1_000L
    private val households = InMemoryHouseholds()
    private val service = HouseholdService(households) { now }

    @Test
    fun `an invitation forms the inviter's household and lets the invited person join it once`() {
        val invitation = service.invite(DONGHOON.userId)

        assertEquals(listOf(DONGHOON, SEUNGMIN), service.join(SEUNGMIN.userId, invitation.code))
        assertEquals(listOf(DONGHOON, SEUNGMIN), service.membersOf(DONGHOON.userId))
        assertFailsWith<InvalidInvitationException> { service.join(GUEST.userId, invitation.code) }
    }

    @Test
    fun `a code is valid for a day and may be typed in lower case with spaces around it`() {
        val invitation = service.invite(DONGHOON.userId)

        assertEquals(Instant.ofEpochMilli(now + Duration.ofDays(1).toMillis()), invitation.expiresAt)
        service.join(SEUNGMIN.userId, "  ${invitation.code.lowercase()} ")
        assertEquals(listOf(DONGHOON, SEUNGMIN), service.membersOf(SEUNGMIN.userId))
    }

    @Test
    fun `an expired code is refused`() {
        val invitation = service.invite(DONGHOON.userId)
        now += Duration.ofDays(1).toMillis()

        assertFailsWith<InvalidInvitationException> { service.join(SEUNGMIN.userId, invitation.code) }
    }

    @Test
    fun `a second invitation invites to the same household`() {
        val first = service.invite(DONGHOON.userId)
        service.join(SEUNGMIN.userId, first.code)

        service.join(GUEST.userId, service.invite(SEUNGMIN.userId).code)

        assertEquals(listOf(GUEST, DONGHOON, SEUNGMIN), service.membersOf(DONGHOON.userId))
    }

    @Test
    fun `someone alone in a household moves, but someone sharing one is refused`() {
        val seungminsOwn = service.invite(SEUNGMIN.userId)
        service.join(DONGHOON.userId, seungminsOwn.code)

        val guestsInvitation = service.invite(GUEST.userId)
        assertFailsWith<AlreadyInHouseholdException> { service.join(DONGHOON.userId, guestsInvitation.code) }
        assertEquals(listOf(DONGHOON, SEUNGMIN), service.membersOf(DONGHOON.userId))
    }

    @Test
    fun `someone in no household has no members`() {
        assertTrue(service.membersOf(GUEST.userId).isEmpty())
    }

    /** Keeps households as the storage does: one household per user, invitations by code hash. */
    private class InMemoryHouseholds : HouseholdRepository {
        private val membership = linkedMapOf<UserId, HouseholdId>()
        private val invitations = linkedMapOf<String, StoredInvitation>()

        override fun findHouseholdOf(userId: UserId): HouseholdId? = membership[userId]

        override fun findMembersOf(userId: UserId): List<RegisteredUser> {
            val household = membership[userId] ?: return emptyList()
            return membership.filterValues { it == household }.keys.map(USERS::getValue).sortedBy { it.displayName }
        }

        override fun findUnexpiredInvitation(codeHash: String, now: Long): StoredInvitation? =
            invitations[codeHash]?.takeIf { it.expiresAt > now }

        override fun save(membership: HouseholdMembership): HouseholdMembership {
            this.membership[membership.userId] = membership.householdId
            return membership
        }

        override fun save(invitation: StoredInvitation): StoredInvitation {
            invitations[invitation.codeHash] = invitation
            return invitation
        }

        override fun deleteInvitationByCodeHash(codeHash: String) {
            invitations.remove(codeHash)
        }
    }

    private companion object {
        val DONGHOON = RegisteredUser(UserId("member-1"), "김동훈")
        val SEUNGMIN = RegisteredUser(UserId("member-2"), "홍승민")
        val GUEST = RegisteredUser(UserId("member-3"), "Guest")
        val USERS = listOf(DONGHOON, SEUNGMIN, GUEST).associateBy { it.userId }
    }
}

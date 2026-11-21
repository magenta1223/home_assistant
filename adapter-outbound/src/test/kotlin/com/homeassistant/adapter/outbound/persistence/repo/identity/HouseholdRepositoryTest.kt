package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.domain.household.HouseholdId
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.identity.port.outbound.HouseholdMembership
import com.homeassistant.feature.identity.port.outbound.PasswordHash
import com.homeassistant.feature.identity.port.outbound.StoredInvitation
import com.homeassistant.feature.identity.port.outbound.UserRegistration
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HouseholdRepositoryTest {
    @Test
    fun `members of a household are found by any member, ordered by name`() = withStores { stores ->
        val households = stores.households
        households.save(HouseholdMembership(HOME, SEUNGMIN.userId, 1))
        households.save(HouseholdMembership(HOME, DONGHOON.userId, 2))
        households.save(HouseholdMembership(OTHER_HOME, GUEST.userId, 3))

        assertEquals(listOf(DONGHOON, SEUNGMIN), households.findMembersOf(DONGHOON.userId))
        assertEquals(listOf(GUEST), households.findMembersOf(GUEST.userId))
        assertEquals(HOME, households.findHouseholdOf(SEUNGMIN.userId))
    }

    @Test
    fun `someone in no household has no household and no members`() = withStores { stores ->
        assertNull(stores.households.findHouseholdOf(GUEST.userId))
        assertTrue(stores.households.findMembersOf(GUEST.userId).isEmpty())
    }

    @Test
    fun `saving a membership in another household moves the user`() = withStores { stores ->
        val households = stores.households
        households.save(HouseholdMembership(OTHER_HOME, DONGHOON.userId, 1))
        households.save(HouseholdMembership(HOME, SEUNGMIN.userId, 2))

        households.save(HouseholdMembership(HOME, DONGHOON.userId, 3))

        assertEquals(listOf(DONGHOON, SEUNGMIN), households.findMembersOf(DONGHOON.userId))
    }

    @Test
    fun `an invitation is found until it expires and not after it is deleted`() = withStores { stores ->
        val households = stores.households
        households.save(HouseholdMembership(HOME, DONGHOON.userId, 1))
        val invitation = StoredInvitation("code-hash", HOME, expiresAt = 100)
        households.save(invitation)

        assertEquals(invitation, households.findUnexpiredInvitation("code-hash", now = 99))
        assertNull(households.findUnexpiredInvitation("code-hash", now = 100))
        households.deleteInvitationByCodeHash("code-hash")
        assertNull(households.findUnexpiredInvitation("code-hash", now = 0))
    }

    private fun withStores(test: (RepositoryStores) -> Unit) {
        val path = Files.createTempFile("households", ".db")
        try {
            val stores = RepositoryFactory.create(path.toString())
            listOf(DONGHOON, SEUNGMIN, GUEST).forEach { stores.users.save(UserRegistration(it, PasswordHash("hash"))) }
            test(stores)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val HOME = HouseholdId("household-1")
        val OTHER_HOME = HouseholdId("household-2")
        val DONGHOON = RegisteredUser(UserId("member-1"), "김동훈")
        val SEUNGMIN = RegisteredUser(UserId("member-2"), "홍승민")
        val GUEST = RegisteredUser(UserId("member-3"), "Guest")
    }
}

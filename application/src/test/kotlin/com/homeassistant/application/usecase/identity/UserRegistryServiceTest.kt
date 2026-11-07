package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.outbound.identity.UserRegistration
import com.homeassistant.application.port.outbound.identity.UserRepository
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserRegistryServiceTest {
    @Test
    fun `lists stored users and authorizes only registered ones`() {
        val member = RegisteredUser(UserId("member-1"), "홍길동")
        val service = UserRegistryService(FixedUsers(member))

        assertEquals(listOf(member), service.list())
        assertTrue(service.isAuthorized(UserId("member-1")))
        assertFalse(service.isAuthorized(UserId("member-2")))
    }

    private class FixedUsers(private vararg val users: RegisteredUser) : UserRepository {
        override fun findAll(): List<RegisteredUser> = users.toList()

        override fun findById(userId: UserId): RegisteredUser? = users.find { it.userId == userId }

        override fun findCredentialByName(name: String) = error("unused")

        override fun save(registration: UserRegistration) = error("unused")
    }
}

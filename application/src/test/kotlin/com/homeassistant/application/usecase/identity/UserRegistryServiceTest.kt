package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.output.identity.UserStore
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
        val service = UserRegistryService(FixedStore(member))

        assertEquals(listOf(member), service.list())
        assertTrue(service.isAuthorized(UserId("member-1")))
        assertFalse(service.isAuthorized(UserId("member-2")))
    }

    private class FixedStore(private vararg val users: RegisteredUser) : UserStore {
        override fun list(): List<RegisteredUser> = users.toList()

        override fun isRegistered(userId: UserId): Boolean = users.any { it.userId == userId }
    }
}

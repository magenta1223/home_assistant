package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.RegisterUserRequest
import com.homeassistant.application.port.output.identity.UserStore
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserRegistryServiceTest {
    @Test
    fun `registration trims the name and authorizes the stored member`() {
        val store = RecordingStore()
        val service = UserRegistryService(store, { UserId("member-1") }, { 123L })

        val member = service.register(RegisterUserRequest(IDENTITY, " 홍길동 "))

        assertEquals(RegisteredUser(UserId("member-1"), "홍길동"), member)
        assertTrue(service.isAuthorized(UserId("member-1")))
        assertEquals(123L, store.registrationNow)
    }

    @Test
    fun `blank and oversized names are rejected before persistence`() {
        val service = UserRegistryService(RecordingStore(), { UserId("member-1") })

        assertFailsWith<IllegalArgumentException> {
            service.register(RegisterUserRequest(IDENTITY, "  "))
        }
        assertFailsWith<IllegalArgumentException> {
            service.register(RegisterUserRequest(IDENTITY, "x".repeat(51)))
        }
    }

    private class RecordingStore : UserStore {
        private val users = mutableMapOf<ConversationIdentity, RegisteredUser>()
        var registrationNow: Long? = null

        override fun find(identity: ConversationIdentity): RegisteredUser? = users[identity]

        override fun register(
            identity: ConversationIdentity,
            proposedUserId: UserId,
            displayName: String,
            now: Long,
        ): RegisteredUser {
            registrationNow = now
            return RegisteredUser(users[identity]?.userId ?: proposedUserId, displayName)
                .also { users[identity] = it }
        }

        override fun list(): List<RegisteredUser> = users.values.toList()

        override fun isRegistered(userId: UserId): Boolean = users.values.any { it.userId == userId }

    }

    private companion object {
        val IDENTITY = ConversationIdentity("http", "user-1")
    }
}

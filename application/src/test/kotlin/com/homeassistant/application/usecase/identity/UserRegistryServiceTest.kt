package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.RegisterUserRequest
import com.homeassistant.application.port.output.identity.UserStore
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `legacy reservation preserves the existing application user`() {
        val store = RecordingStore()
        val service = UserRegistryService(store, { UserId("new-user") }, { 456L })

        service.reserveLegacy(IDENTITY, UserId("existing-user"))
        val member = service.register(RegisterUserRequest(IDENTITY, "이름"))

        assertEquals("existing-user", member.userId.value)
        assertTrue(service.isAuthorized(UserId("existing-user")))
        assertFalse(service.isAuthorized(UserId("new-user")))
    }

    private class RecordingStore : UserStore {
        private val reservations = mutableMapOf<ConversationIdentity, UserId>()
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
            return RegisteredUser(reservations[identity] ?: proposedUserId, displayName)
                .also { users[identity] = it }
        }

        override fun list(): List<RegisteredUser> = users.values.toList()

        override fun isRegistered(userId: UserId): Boolean = users.values.any { it.userId == userId }

        override fun reserve(identity: ConversationIdentity, userId: UserId, now: Long) {
            reservations.putIfAbsent(identity, userId)
        }
    }

    private companion object {
        val IDENTITY = ConversationIdentity("team-1", "slack-1")
    }
}

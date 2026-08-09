package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.RegisterHouseholdMemberRequest
import com.homeassistant.application.port.output.identity.HouseholdMemberStore
import com.homeassistant.domain.identity.HouseholdMember
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HouseholdMemberServiceTest {
    @Test
    fun `registration trims the name and authorizes the stored member`() {
        val store = RecordingStore()
        val service = HouseholdMemberService(store, { UserId("member-1") }, { 123L })

        val member = service.register(RegisterHouseholdMemberRequest(IDENTITY, " 홍길동 "))

        assertEquals(HouseholdMember(UserId("member-1"), "홍길동"), member)
        assertTrue(service.isAuthorized(UserId("member-1")))
        assertEquals(123L, store.registrationNow)
    }

    @Test
    fun `blank and oversized names are rejected before persistence`() {
        val service = HouseholdMemberService(RecordingStore(), { UserId("member-1") })

        assertFailsWith<IllegalArgumentException> {
            service.register(RegisterHouseholdMemberRequest(IDENTITY, "  "))
        }
        assertFailsWith<IllegalArgumentException> {
            service.register(RegisterHouseholdMemberRequest(IDENTITY, "x".repeat(51)))
        }
    }

    @Test
    fun `legacy reservation preserves the existing application user`() {
        val store = RecordingStore()
        val service = HouseholdMemberService(store, { UserId("new-user") }, { 456L })

        service.reserveLegacy(IDENTITY, UserId("existing-user"))
        val member = service.register(RegisterHouseholdMemberRequest(IDENTITY, "이름"))

        assertEquals("existing-user", member.userId.value)
        assertTrue(service.isAuthorized(UserId("existing-user")))
        assertFalse(service.isAuthorized(UserId("new-user")))
    }

    private class RecordingStore : HouseholdMemberStore {
        private val reservations = mutableMapOf<ConversationIdentity, UserId>()
        private val members = mutableMapOf<ConversationIdentity, HouseholdMember>()
        var registrationNow: Long? = null

        override fun find(identity: ConversationIdentity): HouseholdMember? = members[identity]

        override fun register(
            identity: ConversationIdentity,
            proposedUserId: UserId,
            displayName: String,
            now: Long,
        ): HouseholdMember {
            registrationNow = now
            return HouseholdMember(reservations[identity] ?: proposedUserId, displayName)
                .also { members[identity] = it }
        }

        override fun list(): List<HouseholdMember> = members.values.toList()

        override fun isRegistered(userId: UserId): Boolean = members.values.any { it.userId == userId }

        override fun reserve(identity: ConversationIdentity, userId: UserId, now: Long) {
            reservations.putIfAbsent(identity, userId)
        }
    }

    private companion object {
        val IDENTITY = ConversationIdentity("team-1", "slack-1")
    }
}

package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HouseholdMemberRepositoryTest {
    @Test
    fun `registration persists identity name and authorization across repository instances`() {
        withStore { path, store ->
            val member = store.register(IDENTITY, UserId("member-1"), "홍길동", 100L)

            assertEquals("member-1", member.userId.value)
            assertEquals("홍길동", member.displayName)

            val reopened = RepositoryFactory.create(path.toString()).householdMembers
            assertEquals(member, reopened.find(IDENTITY))
            assertEquals(listOf(member), reopened.list())
            assertTrue(reopened.isRegistered(UserId("member-1")))
        }
    }

    @Test
    fun `legacy reservation is invisible until named and preserves its userId`() {
        withStore { _, store ->
            store.reserve(IDENTITY, UserId("legacy-user"), 100L)

            assertNull(store.find(IDENTITY))
            assertTrue(store.list().isEmpty())
            assertFalse(store.isRegistered(UserId("legacy-user")))

            val member = store.register(IDENTITY, UserId("new-user"), "기존 사용자", 101L)

            assertEquals("legacy-user", member.userId.value)
            assertTrue(store.isRegistered(UserId("legacy-user")))
            assertFalse(store.isRegistered(UserId("new-user")))
        }
    }

    @Test
    fun `registering the same Slack identity again keeps its userId and updates its name`() {
        withStore { _, store ->
            store.register(IDENTITY, UserId("member-1"), "처음 이름", 100L)

            val updated = store.register(IDENTITY, UserId("member-2"), "새 이름", 101L)

            assertEquals("member-1", updated.userId.value)
            assertEquals("새 이름", updated.displayName)
            assertFalse(store.isRegistered(UserId("member-2")))
        }
    }

    private fun withStore(block: (java.nio.file.Path, com.homeassistant.application.port.output.identity.HouseholdMemberStore) -> Unit) {
        val path = Files.createTempFile("household-member", ".db")
        try {
            block(path, RepositoryFactory.create(path.toString()).householdMembers)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val IDENTITY = ConversationIdentity("team-1", "slack-1")
    }
}

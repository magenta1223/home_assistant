package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRepositoryTest {
    @Test
    fun `legacy user table is renamed without losing registrations`() {
        val path = Files.createTempFile("legacy-user-table", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "CREATE TABLE household_members (" +
                            "user_id TEXT PRIMARY KEY, display_name TEXT, created_at INTEGER, updated_at INTEGER)",
                    )
                    statement.executeUpdate(
                        "CREATE TABLE conversation_identities (" +
                            "scope_id TEXT, participant_id TEXT, user_id TEXT, created_at INTEGER, " +
                            "PRIMARY KEY (scope_id, participant_id), " +
                            "FOREIGN KEY (user_id) REFERENCES household_members(user_id))",
                    )
                    statement.executeUpdate(
                        "INSERT INTO household_members VALUES ('member-1', '기존 사용자', 100, 100)",
                    )
                    statement.executeUpdate(
                        "INSERT INTO conversation_identities VALUES ('team-1', 'slack-1', 'member-1', 100)",
                    )
                }
            }

            val store = RepositoryFactory.create(path.toString()).users

            assertEquals("기존 사용자", store.find(IDENTITY)?.displayName)
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                ).use { statement ->
                    statement.setString(1, "registered_users")
                    assertTrue(statement.executeQuery().use { it.next() })
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `registration persists identity name and authorization across repository instances`() {
        withStore { path, store ->
            val member = store.register(IDENTITY, UserId("member-1"), "홍길동", 100L)

            assertEquals("member-1", member.userId.value)
            assertEquals("홍길동", member.displayName)

            val reopened = RepositoryFactory.create(path.toString()).users
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

    private fun withStore(block: (java.nio.file.Path, com.homeassistant.application.port.output.identity.UserStore) -> Unit) {
        val path = Files.createTempFile("registered-user", ".db")
        try {
            block(path, RepositoryFactory.create(path.toString()).users)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val IDENTITY = ConversationIdentity("team-1", "slack-1")
    }
}

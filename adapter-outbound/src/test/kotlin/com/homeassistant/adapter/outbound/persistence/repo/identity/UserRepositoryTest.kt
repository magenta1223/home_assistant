package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                        "INSERT INTO household_members VALUES ('member-1', '기존 사용자', 100, 100)",
                    )
                }
            }

            val store = RepositoryFactory.create(path.toString()).users

            assertEquals(listOf(RegisteredUser(UserId("member-1"), "기존 사용자")), store.list())
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
    fun `only users with a display name are listed and authorized`() {
        val path = Files.createTempFile("registered-user", ".db")
        try {
            val store = RepositoryFactory.create(path.toString()).users
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("INSERT INTO registered_users VALUES ('member-1', '홍길동', 100, 100)")
                    statement.executeUpdate("INSERT INTO registered_users VALUES ('member-2', NULL, 100, 100)")
                }
            }

            assertEquals(listOf(RegisteredUser(UserId("member-1"), "홍길동")), store.list())
            assertTrue(store.isRegistered(UserId("member-1")))
            assertFalse(store.isRegistered(UserId("member-2")))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

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

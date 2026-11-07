package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.outbound.identity.PasswordHash
import com.homeassistant.application.port.outbound.identity.StoredCredential
import com.homeassistant.application.port.outbound.identity.UserNameConflict
import com.homeassistant.application.port.outbound.identity.UserRegistration
import com.homeassistant.application.port.outbound.identity.UserRepository
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UserRepositoryTest {
    @Test
    fun `a display name can be registered once and is found with its hash`() = withUsers { users, _ ->
        assertEquals(FIRST, users.save(UserRegistration(FIRST, HASH)))
        assertEquals(SECOND, users.save(UserRegistration(SECOND, HASH)))
        assertFailsWith<UserNameConflict> {
            users.save(UserRegistration(RegisteredUser(UserId("member-3"), SECOND.displayName), HASH))
        }

        assertEquals(listOf(FIRST, SECOND).sortedBy { it.displayName }, users.findAll())
        assertEquals(FIRST, users.findById(FIRST.userId))
        assertNull(users.findById(UserId("member-3")))
        assertEquals(StoredCredential(SECOND.userId, HASH), users.findCredentialByName(SECOND.displayName))
        assertNull(users.findCredentialByName("없는사람"))
    }

    @Test
    fun `only users with a display name are found`() = withUsers { users, path ->
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO registered_users VALUES ('member-1', '홍길동', 100, 100)")
                statement.executeUpdate("INSERT INTO registered_users VALUES ('member-2', NULL, 100, 100)")
            }
        }

        assertEquals(listOf(RegisteredUser(UserId("member-1"), "홍길동")), users.findAll())
        assertEquals(RegisteredUser(UserId("member-1"), "홍길동"), users.findById(UserId("member-1")))
        assertNull(users.findById(UserId("member-2")))
    }

    private fun withUsers(block: (UserRepository, Path) -> Unit) {
        val path = Files.createTempFile("registered-user", ".db")
        try {
            block(RepositoryFactory.create(path.toString()).users, path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val FIRST = RegisteredUser(UserId("member-1"), "김동훈")
        val SECOND = RegisteredUser(UserId("member-2"), "홍승민")
        val HASH = PasswordHash("encoded-hash")
    }
}

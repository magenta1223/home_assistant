package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.db.tables.UserCredentialTable
import com.homeassistant.adapter.outbound.persistence.db.tables.UserTable
import com.homeassistant.application.port.outbound.identity.PasswordHash
import com.homeassistant.application.port.outbound.identity.StoredCredential
import com.homeassistant.application.port.outbound.identity.UserNameConflict
import com.homeassistant.application.port.outbound.identity.UserRegistration
import com.homeassistant.application.port.outbound.identity.UserRepository
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.trim

internal class UserRepositoryImpl(
    private val db: SqliteDatabases,
    private val clock: () -> Long = System::currentTimeMillis,
) : UserRepository {
    override fun findAll(): List<RegisteredUser> = db.read {
        UserTable.selectAll()
            .where { hasDisplayName() }
            .orderBy(UserTable.displayName.lowerCase())
            .map { it.toRegisteredUser() }
    }

    override fun findById(userId: UserId): RegisteredUser? = db.read {
        UserTable.selectAll()
            .where { (UserTable.userId eq userId.value) and hasDisplayName() }
            .singleOrNull()
            ?.toRegisteredUser()
    }

    override fun findCredentialByName(name: String): StoredCredential? = db.read {
        UserTable.join(UserCredentialTable, JoinType.INNER, UserTable.userId, UserCredentialTable.userId)
            .selectAll()
            .where { UserTable.displayName eq name }
            .singleOrNull()
            ?.let { StoredCredential(UserId(it[UserTable.userId]), PasswordHash(it[UserCredentialTable.passwordHash])) }
    }

    override fun save(registration: UserRegistration): RegisteredUser = db.write {
        val user = registration.user
        val nameTaken = UserTable.selectAll().where { UserTable.displayName eq user.displayName }.any()
        if (nameTaken) throw UserNameConflict(user.displayName)
        insert(user, registration.passwordHash)
        user
    }

    private fun insert(user: RegisteredUser, passwordHash: PasswordHash) {
        val now = clock()
        UserTable.insert {
            it[userId] = user.userId.value
            it[displayName] = user.displayName
            it[createdAt] = now
            it[updatedAt] = now
        }
        UserCredentialTable.insert {
            it[userId] = user.userId.value
            it[UserCredentialTable.passwordHash] = passwordHash.encoded
        }
    }

    private fun hasDisplayName(): Op<Boolean> =
        UserTable.displayName.isNotNull() and (UserTable.displayName.trim() neq "")

    private fun ResultRow.toRegisteredUser(): RegisteredUser =
        RegisteredUser(
            userId = UserId(this[UserTable.userId]),
            displayName = checkNotNull(this[UserTable.displayName]),
        )
}

package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.db.tables.UserCredentialTable
import com.homeassistant.adapter.outbound.persistence.db.tables.UserTable
import com.homeassistant.application.port.output.identity.PasswordHash
import com.homeassistant.application.port.output.identity.StoredCredential
import com.homeassistant.application.port.output.identity.UserCredentialStore
import com.homeassistant.application.port.output.identity.UserRegistration
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

internal class UserCredentialRepository(
    private val db: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) : UserCredentialStore {
    override fun hasUsers(): Boolean = transaction(db) { anyUser() }

    override fun registerFirst(user: RegisteredUser, passwordHash: PasswordHash): UserRegistration = transaction(db) {
        if (anyUser()) return@transaction UserRegistration.USERS_ALREADY_EXIST
        insert(user, passwordHash)
        UserRegistration.REGISTERED
    }

    override fun register(user: RegisteredUser, passwordHash: PasswordHash): UserRegistration = transaction(db) {
        val nameTaken = UserTable.selectAll().where { UserTable.displayName eq user.displayName }.any()
        if (nameTaken) return@transaction UserRegistration.NAME_TAKEN
        insert(user, passwordHash)
        UserRegistration.REGISTERED
    }

    override fun findByName(name: String): StoredCredential? = transaction(db) {
        UserTable.join(UserCredentialTable, JoinType.INNER, UserTable.userId, UserCredentialTable.userId)
            .selectAll()
            .where { UserTable.displayName eq name }
            .singleOrNull()
            ?.let { StoredCredential(UserId(it[UserTable.userId]), PasswordHash(it[UserCredentialTable.passwordHash])) }
    }

    private fun anyUser(): Boolean = UserTable.selectAll().limit(1).any()

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
}

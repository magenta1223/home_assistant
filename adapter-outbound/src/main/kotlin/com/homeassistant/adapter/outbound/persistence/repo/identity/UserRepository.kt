package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.db.tables.ConversationIdentityTable
import com.homeassistant.adapter.outbound.persistence.db.tables.UserTable
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.output.identity.UserStore
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

internal class UserRepository(
    private val db: Database,
) : UserStore {
    override fun find(identity: ConversationIdentity): RegisteredUser? = transaction(db) {
        identityRow(identity)
            ?.let { memberRow(it[ConversationIdentityTable.userId]) }
            ?.toRegisteredUser()
    }

    override fun register(
        identity: ConversationIdentity,
        proposedUserId: UserId,
        displayName: String,
        now: Long,
    ): RegisteredUser = transaction(db) {
        val existingIdentity = identityRow(identity)
        val userId = if (existingIdentity == null) {
            UserTable.insert {
                it[UserTable.userId] = proposedUserId.value
                it[UserTable.displayName] = displayName
                it[createdAt] = now
                it[updatedAt] = now
            }
            ConversationIdentityTable.insert {
                it[scopeId] = identity.scopeId
                it[participantId] = identity.participantId
                it[ConversationIdentityTable.userId] = proposedUserId.value
                it[createdAt] = now
            }
            proposedUserId.value
        } else {
            existingIdentity[ConversationIdentityTable.userId]
        }

        UserTable.update({ UserTable.userId eq userId }) {
            it[UserTable.displayName] = displayName
            it[updatedAt] = now
        }
        requireNotNull(memberRow(userId).toRegisteredUser())
    }

    override fun list(): List<RegisteredUser> = transaction(db) {
        UserTable.selectAll()
            .mapNotNull { it.toRegisteredUser() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RegisteredUser::displayName))
    }

    override fun isRegistered(userId: UserId): Boolean = transaction(db) {
        memberRow(userId.value)?.toRegisteredUser() != null
    }

    override fun reserve(identity: ConversationIdentity, userId: UserId, now: Long) {
        transaction(db) {
            UserTable.insertIgnore {
                it[UserTable.userId] = userId.value
                it[displayName] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
            ConversationIdentityTable.insertIgnore {
                it[scopeId] = identity.scopeId
                it[participantId] = identity.participantId
                it[ConversationIdentityTable.userId] = userId.value
                it[createdAt] = now
            }
        }
    }

    private fun identityRow(identity: ConversationIdentity): ResultRow? =
        ConversationIdentityTable.selectAll()
            .where {
                (ConversationIdentityTable.scopeId eq identity.scopeId) and
                    (ConversationIdentityTable.participantId eq identity.participantId)
            }
            .singleOrNull()

    private fun memberRow(userId: String): ResultRow? =
        UserTable.selectAll()
            .where { UserTable.userId eq userId }
            .singleOrNull()

    private fun ResultRow?.toRegisteredUser(): RegisteredUser? {
        val row = this ?: return null
        val displayName = row[UserTable.displayName]?.takeIf(String::isNotBlank) ?: return null
        return RegisteredUser(
            userId = UserId(row[UserTable.userId]),
            displayName = displayName,
        )
    }
}

package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.db.tables.ConversationIdentityTable
import com.homeassistant.adapter.outbound.persistence.db.tables.HouseholdMemberTable
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.output.identity.HouseholdMemberStore
import com.homeassistant.domain.identity.HouseholdMember
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

internal class HouseholdMemberRepository(
    private val db: Database,
) : HouseholdMemberStore {
    override fun find(identity: ConversationIdentity): HouseholdMember? = transaction(db) {
        identityRow(identity)
            ?.let { memberRow(it[ConversationIdentityTable.userId]) }
            ?.toRegisteredMember()
    }

    override fun register(
        identity: ConversationIdentity,
        proposedUserId: UserId,
        displayName: String,
        now: Long,
    ): HouseholdMember = transaction(db) {
        val existingIdentity = identityRow(identity)
        val userId = if (existingIdentity == null) {
            HouseholdMemberTable.insert {
                it[HouseholdMemberTable.userId] = proposedUserId.value
                it[HouseholdMemberTable.displayName] = displayName
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

        HouseholdMemberTable.update({ HouseholdMemberTable.userId eq userId }) {
            it[HouseholdMemberTable.displayName] = displayName
            it[updatedAt] = now
        }
        requireNotNull(memberRow(userId).toRegisteredMember())
    }

    override fun list(): List<HouseholdMember> = transaction(db) {
        HouseholdMemberTable.selectAll()
            .mapNotNull { it.toRegisteredMember() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, HouseholdMember::displayName))
    }

    override fun isRegistered(userId: UserId): Boolean = transaction(db) {
        memberRow(userId.value)?.toRegisteredMember() != null
    }

    override fun reserve(identity: ConversationIdentity, userId: UserId, now: Long) {
        transaction(db) {
            HouseholdMemberTable.insertIgnore {
                it[HouseholdMemberTable.userId] = userId.value
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
        HouseholdMemberTable.selectAll()
            .where { HouseholdMemberTable.userId eq userId }
            .singleOrNull()

    private fun ResultRow?.toRegisteredMember(): HouseholdMember? {
        val row = this ?: return null
        val displayName = row[HouseholdMemberTable.displayName]?.takeIf(String::isNotBlank) ?: return null
        return HouseholdMember(
            userId = UserId(row[HouseholdMemberTable.userId]),
            displayName = displayName,
        )
    }
}

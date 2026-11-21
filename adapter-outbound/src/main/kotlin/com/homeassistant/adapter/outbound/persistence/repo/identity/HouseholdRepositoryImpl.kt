package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.db.tables.HouseholdInvitationTable
import com.homeassistant.adapter.outbound.persistence.db.tables.HouseholdMemberTable
import com.homeassistant.adapter.outbound.persistence.db.tables.HouseholdTable
import com.homeassistant.adapter.outbound.persistence.db.tables.UserTable
import com.homeassistant.domain.household.HouseholdId
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.finance.port.outbound.HouseholdMembers
import com.homeassistant.feature.identity.port.outbound.HouseholdMembership
import com.homeassistant.feature.identity.port.outbound.HouseholdRepository
import com.homeassistant.feature.identity.port.outbound.StoredInvitation
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

/** Households for forming them by invitation and for sharing finances among their members. */
internal class HouseholdRepositoryImpl(private val db: SqliteDatabases) : HouseholdRepository, HouseholdMembers {

    override fun findHouseholdOf(userId: UserId): HouseholdId? = db.read {
        HouseholdMemberTable.select(HouseholdMemberTable.householdId)
            .where { HouseholdMemberTable.userId eq userId.value }
            .singleOrNull()
            ?.let { HouseholdId(it[HouseholdMemberTable.householdId]) }
    }

    override fun findMembersOf(userId: UserId): List<RegisteredUser> = db.read {
        val householdOfUser = HouseholdMemberTable.select(HouseholdMemberTable.householdId)
            .where { HouseholdMemberTable.userId eq userId.value }
        HouseholdMemberTable.join(UserTable, JoinType.INNER, HouseholdMemberTable.userId, UserTable.userId)
            .select(UserTable.userId, UserTable.displayName)
            .where { HouseholdMemberTable.householdId inSubQuery householdOfUser }
            .orderBy(UserTable.displayName.lowerCase())
            .map { RegisteredUser(UserId(it[UserTable.userId]), checkNotNull(it[UserTable.displayName])) }
    }

    override fun findUnexpiredInvitation(codeHash: String, now: Long): StoredInvitation? = db.read {
        HouseholdInvitationTable.selectAll()
            .where { (HouseholdInvitationTable.codeHash eq codeHash) and (HouseholdInvitationTable.expiresAt greater now) }
            .singleOrNull()
            ?.let {
                StoredInvitation(
                    codeHash = it[HouseholdInvitationTable.codeHash],
                    householdId = HouseholdId(it[HouseholdInvitationTable.householdId]),
                    expiresAt = it[HouseholdInvitationTable.expiresAt],
                )
            }
    }

    override fun save(membership: HouseholdMembership): HouseholdMembership = db.write {
        HouseholdTable.insertIgnore {
            it[householdId] = membership.householdId.value
            it[createdAt] = membership.joinedAt
        }
        HouseholdMemberTable.upsert {
            it[householdId] = membership.householdId.value
            it[userId] = membership.userId.value
            it[joinedAt] = membership.joinedAt
        }
        membership
    }

    override fun save(invitation: StoredInvitation): StoredInvitation = db.write {
        HouseholdInvitationTable.insert {
            it[codeHash] = invitation.codeHash
            it[householdId] = invitation.householdId.value
            it[expiresAt] = invitation.expiresAt
        }
        invitation
    }

    override fun deleteInvitationByCodeHash(codeHash: String) {
        db.write { HouseholdInvitationTable.deleteWhere { HouseholdInvitationTable.codeHash eq codeHash } }
    }
}

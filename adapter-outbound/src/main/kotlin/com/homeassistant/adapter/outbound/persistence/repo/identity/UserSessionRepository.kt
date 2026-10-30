package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.db.tables.UserSessionTable
import com.homeassistant.application.port.output.identity.StoredSession
import com.homeassistant.application.port.output.identity.UserSessionStore
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

internal class UserSessionRepository(
    private val db: Database,
) : UserSessionStore {
    override fun save(session: StoredSession) {
        transaction(db) {
            UserSessionTable.insert {
                it[idHash] = session.idHash
                it[userId] = session.userId.value
                it[expiresAt] = session.expiresAt
            }
        }
    }

    override fun findUser(idHash: String, now: Long): UserId? = transaction(db) {
        UserSessionTable.selectAll()
            .where { (UserSessionTable.idHash eq idHash) and (UserSessionTable.expiresAt greater now) }
            .singleOrNull()
            ?.let { UserId(it[UserSessionTable.userId]) }
    }

    override fun delete(idHash: String) {
        transaction(db) { UserSessionTable.deleteWhere { UserSessionTable.idHash eq idHash } }
    }

    override fun deleteExpired(now: Long) {
        transaction(db) { UserSessionTable.deleteWhere { expiresAt lessEq now } }
    }
}

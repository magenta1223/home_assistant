package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.db.tables.UserSessionTable
import com.homeassistant.application.port.outbound.identity.StoredSession
import com.homeassistant.application.port.outbound.identity.UserSessionRepository
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

internal class UserSessionRepositoryImpl(
    private val db: SqliteDatabases,
) : UserSessionRepository {
    override fun findUnexpired(idHash: String, now: Long): StoredSession? = db.read {
        UserSessionTable.selectAll()
            .where { (UserSessionTable.idHash eq idHash) and (UserSessionTable.expiresAt greater now) }
            .singleOrNull()
            ?.let {
                StoredSession(
                    idHash = it[UserSessionTable.idHash],
                    userId = UserId(it[UserSessionTable.userId]),
                    expiresAt = it[UserSessionTable.expiresAt],
                )
            }
    }

    override fun save(session: StoredSession): StoredSession = db.write {
        UserSessionTable.insert {
            it[idHash] = session.idHash
            it[userId] = session.userId.value
            it[expiresAt] = session.expiresAt
        }
        session
    }

    override fun deleteByIdHash(idHash: String) {
        db.write { UserSessionTable.deleteWhere { UserSessionTable.idHash eq idHash } }
    }

    override fun deleteExpiredBy(now: Long) {
        db.write { UserSessionTable.deleteWhere { expiresAt lessEq now } }
    }
}

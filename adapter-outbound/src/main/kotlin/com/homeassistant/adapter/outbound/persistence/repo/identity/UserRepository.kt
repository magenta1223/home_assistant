package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.db.tables.UserTable
import com.homeassistant.application.port.output.identity.UserStore
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

internal class UserRepository(
    private val db: Database,
) : UserStore {
    override fun list(): List<RegisteredUser> = transaction(db) {
        UserTable.selectAll()
            .mapNotNull { it.toRegisteredUser() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RegisteredUser::displayName))
    }

    override fun isRegistered(userId: UserId): Boolean = transaction(db) {
        memberRow(userId.value)?.toRegisteredUser() != null
    }

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

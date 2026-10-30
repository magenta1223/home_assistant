package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object UserTable : Table("registered_users") {
    val userId = text("user_id")
    val displayName = text("display_name").nullable().uniqueIndex()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

internal object UserCredentialTable : Table("user_credentials") {
    val userId = text("user_id")
    val passwordHash = text("password_hash")
    override val primaryKey = PrimaryKey(userId)
}

internal object UserSessionTable : Table("user_sessions") {
    val idHash = text("id_hash")
    val userId = text("user_id")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(idHash)
}

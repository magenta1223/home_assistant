package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object UserTable : Table("registered_users") {
    val userId = text("user_id")
    val displayName = text("display_name").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

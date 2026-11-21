package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object HouseholdTable : Table("households") {
    val householdId = text("household_id")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(householdId)
}

internal object HouseholdMemberTable : Table("household_members") {
    val householdId = text("household_id").references(HouseholdTable.householdId)
    val userId = text("user_id")
    val joinedAt = long("joined_at")
    override val primaryKey = PrimaryKey(userId)

    init {
        index(false, householdId)
    }
}

internal object HouseholdInvitationTable : Table("household_invitations") {
    val codeHash = text("code_hash")
    val householdId = text("household_id").references(HouseholdTable.householdId)
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(codeHash)
}

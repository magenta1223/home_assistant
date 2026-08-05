package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object FamilyTable : Table("families") {
    val id = text("id")
    val name = text("name")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object FamilyMemberTable : Table("family_members") {
    val id = text("id")
    val familyId = text("family_id").references(FamilyTable.id)
    val displayName = text("display_name").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object DomainTable : Table("domains") {
    val id = integer("id").autoIncrement()
    val familyId = text("family_id").references(FamilyTable.id)
    val name = text("name")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(familyId, name)
    }
}

internal object ConversationMessageTable : Table("conversation_messages") {
    val id = integer("id").autoIncrement()
    val familyId = text("family_id").references(FamilyTable.id)
    val conversationId = text("conversation_id")
    val userId = text("user_id").nullable()
    val role = text("role")
    val content = text("content")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object MemoryCandidateTable : Table("memory_candidates") {
    val id = integer("id").autoIncrement()
    val familyId = text("family_id").references(FamilyTable.id)
    val conversationId = text("conversation_id")
    val domainId = integer("domain_id").references(DomainTable.id)
    val memoryType = text("memory_type")
    val content = text("content")
    val summary = text("summary")
    val subjectMemberId = text("subject_member_id").nullable()
    val createdBy = text("created_by").references(FamilyMemberTable.id)
    val visibility = text("visibility")
    val confidence = double("confidence")
    val sourceConversationMessageId = integer("source_conversation_message_id").nullable()
    val status = text("status")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object MemoryTable : Table("memories") {
    val id = integer("id").autoIncrement()
    val familyId = text("family_id").references(FamilyTable.id)
    val domainId = integer("domain_id").references(DomainTable.id)
    val memoryType = text("memory_type")
    val content = text("content")
    val summary = text("summary")
    val subjectMemberId = text("subject_member_id").nullable()
    val createdBy = text("created_by").references(FamilyMemberTable.id)
    val visibility = text("visibility")
    val confidence = double("confidence")
    val sourceConversationMessageId = integer("source_conversation_message_id").nullable()
    val sourceCandidateId = integer("source_candidate_id").references(MemoryCandidateTable.id)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object AuditLogTable : Table("audit_logs") {
    val id = integer("id").autoIncrement()
    val familyId = text("family_id").references(FamilyTable.id)
    val actorUserId = text("actor_user_id").nullable()
    val action = text("action")
    val candidateId = integer("candidate_id").nullable()
    val memoryId = integer("memory_id").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

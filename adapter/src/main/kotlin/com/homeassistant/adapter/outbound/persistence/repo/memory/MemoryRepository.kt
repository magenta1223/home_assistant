package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.CandidateStatus
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.adapter.shared.json.JsonSerializer
import com.homeassistant.domain.memory.DEFAULT_FAMILY_ID
import com.homeassistant.domain.memory.MemoryCandidate
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryStore
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.adapter.outbound.persistence.repo.indexing.enqueueIndex
import com.homeassistant.adapter.outbound.persistence.db.tables.*
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class MemoryRepository(private val db: Database) : MemoryStore {
    private val json = JsonSerializer.json

    init {
        transaction(db) {
            ensureDefaultFamily()
            DEFAULT_DOMAIN_NAMES.forEach { upsertDomain(it) }
        }
    }

    override fun createCandidate(
        userId: UserId,
        conversationId: String,
        domainName: String,
        memoryType: MemoryType,
        content: String,
        summary: String,
        confidence: Double,
        sourceConversationMessageId: Int?,
        subjectMemberId: String?,
        visibility: String,
    ): Int = transaction(db) {
        ensureDefaultFamily()
        ensureMember(userId.value)
        val now = System.currentTimeMillis()
        val domainId = upsertDomain(domainName)
        val id = MemoryCandidateTable.insert {
            it[familyId] = DEFAULT_FAMILY_ID
            it[MemoryCandidateTable.conversationId] = conversationId
            it[MemoryCandidateTable.domainId] = domainId
            it[MemoryCandidateTable.memoryType] = memoryType.code
            it[MemoryCandidateTable.content] = content
            it[MemoryCandidateTable.summary] = summary
            it[MemoryCandidateTable.subjectMemberId] = subjectMemberId
            it[createdBy] = userId.value
            it[MemoryCandidateTable.visibility] = visibility
            it[MemoryCandidateTable.confidence] = confidence
            it[MemoryCandidateTable.sourceConversationMessageId] = sourceConversationMessageId
            it[status] = CandidateStatus.PENDING.name
            it[createdAt] = now
            it[updatedAt] = now
        }[MemoryCandidateTable.id]
        audit(AuditAction.CANDIDATE_CREATED, userId.value, id, null)
        id
    }

    override fun listPending(userId: UserId, conversationId: String): List<MemoryCandidate> = transaction(db) {
        ensureMember(userId.value)
        MemoryCandidateTable.selectAll()
            .where {
                (MemoryCandidateTable.createdBy eq userId.value) and
                    (MemoryCandidateTable.conversationId eq conversationId) and
                    (MemoryCandidateTable.status eq CandidateStatus.PENDING.name)
            }
            .map { it.toMemoryCandidate() }
    }

    override fun getCandidate(id: Int): MemoryCandidate? = transaction(db) {
        MemoryCandidateTable.selectAll().where { MemoryCandidateTable.id eq id }
            .singleOrNull()
            ?.toMemoryCandidate()
    }

    override fun approveCandidate(userId: UserId, candidateId: Int): Memory = transaction(db) {
        ensureMember(userId.value)
        val candidate = MemoryCandidateTable.selectAll()
            .where {
                (MemoryCandidateTable.id eq candidateId) and
                    (MemoryCandidateTable.createdBy eq userId.value) and
                    (MemoryCandidateTable.status eq CandidateStatus.PENDING.name)
            }
            .singleOrNull()
            ?: error("Pending candidate not found for user: $candidateId")

        val now = System.currentTimeMillis()
        MemoryCandidateTable.update({ MemoryCandidateTable.id eq candidateId }) {
            it[status] = CandidateStatus.APPROVED.name
            it[updatedAt] = now
        }
        audit(AuditAction.CANDIDATE_APPROVED, userId.value, candidateId, null)
        val memoryId = MemoryTable.insert {
            it[familyId] = candidate[MemoryCandidateTable.familyId]
            it[domainId] = candidate[MemoryCandidateTable.domainId]
            it[memoryType] = candidate[MemoryCandidateTable.memoryType]
            it[content] = candidate[MemoryCandidateTable.content]
            it[summary] = candidate[MemoryCandidateTable.summary]
            it[subjectMemberId] = candidate[MemoryCandidateTable.subjectMemberId]
            it[createdBy] = candidate[MemoryCandidateTable.createdBy]
            it[visibility] = candidate[MemoryCandidateTable.visibility]
            it[confidence] = candidate[MemoryCandidateTable.confidence]
            it[sourceConversationMessageId] = candidate[MemoryCandidateTable.sourceConversationMessageId]
            it[sourceCandidateId] = candidateId
            it[createdAt] = now
            it[updatedAt] = now
        }[MemoryTable.id]
        enqueueIndex(IndexTargetType.MEMORY, memoryId)
        audit(AuditAction.MEMORY_CREATED, userId.value, candidateId, memoryId)
        fetchMemory(memoryId) ?: error("Created memory not found: $memoryId")
    }

    override fun rejectCandidate(userId: UserId, candidateId: Int) = transaction(db) {
        ensureMember(userId.value)
        val updated = MemoryCandidateTable.update({
            (MemoryCandidateTable.id eq candidateId) and
                (MemoryCandidateTable.createdBy eq userId.value) and
                (MemoryCandidateTable.status eq CandidateStatus.PENDING.name)
        }) {
            it[status] = CandidateStatus.REJECTED.name
            it[updatedAt] = System.currentTimeMillis()
        }
        check(updated > 0) { "Pending candidate not found for user: $candidateId" }
        audit(AuditAction.CANDIDATE_REJECTED, userId.value, candidateId, null)
        Unit
    }

    override fun getMemory(id: Int): Memory? = transaction(db) { fetchMemory(id) }

    override fun listMemories(ids: List<Int>?): List<Memory> = transaction(db) {
        val query = if (ids.isNullOrEmpty()) MemoryTable.selectAll()
        else MemoryTable.selectAll().where { MemoryTable.id inList ids }
        query.map { it.toMemory() }
    }

    fun auditLogs(): List<AuditLogRow> = transaction(db) {
        AuditLogTable.selectAll().map {
            AuditLogRow(
                id = it[AuditLogTable.id],
                action = AuditAction.valueOf(it[AuditLogTable.action]),
                candidateId = it[AuditLogTable.candidateId],
                memoryId = it[AuditLogTable.memoryId],
            )
        }
    }

    private fun ensureDefaultFamily() {
        if (FamilyTable.selectAll().where { FamilyTable.id eq DEFAULT_FAMILY_ID }.empty()) {
            FamilyTable.insert {
                it[id] = DEFAULT_FAMILY_ID
                it[name] = DEFAULT_FAMILY_NAME
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    private fun ensureMember(userId: String) {
        ensureDefaultFamily()
        if (FamilyMemberTable.selectAll().where { FamilyMemberTable.id eq userId }.empty()) {
            FamilyMemberTable.insert {
                it[id] = userId
                it[familyId] = DEFAULT_FAMILY_ID
                it[displayName] = userId
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    private fun upsertDomain(name: String): Int {
        val normalized = name.trim().uppercase().ifBlank { "GENERAL" }
        val existing = DomainTable.selectAll()
            .where { (DomainTable.familyId eq DEFAULT_FAMILY_ID) and (DomainTable.name eq normalized) }
            .singleOrNull()
        if (existing != null) return existing[DomainTable.id]
        val now = System.currentTimeMillis()
        return DomainTable.insert {
            it[familyId] = DEFAULT_FAMILY_ID
            it[DomainTable.name] = normalized
            it[createdAt] = now
            it[updatedAt] = now
        }[DomainTable.id]
    }

    private fun audit(action: AuditAction, actorUserId: String?, candidateId: Int?, memoryId: Int?) {
        AuditLogTable.insert {
            it[familyId] = DEFAULT_FAMILY_ID
            it[AuditLogTable.actorUserId] = actorUserId
            it[AuditLogTable.action] = action.name
            it[AuditLogTable.candidateId] = candidateId
            it[AuditLogTable.memoryId] = memoryId
            it[createdAt] = System.currentTimeMillis()
        }
    }

    private fun fetchMemory(id: Int): Memory? =
        MemoryTable.selectAll().where { MemoryTable.id eq id }.singleOrNull()?.toMemory()

    private fun ResultRow.toMemoryCandidate(): MemoryCandidate {
        val domain = DomainTable.selectAll().where { DomainTable.id eq this@toMemoryCandidate[MemoryCandidateTable.domainId] }.single()
        return MemoryCandidate(
            id = this[MemoryCandidateTable.id],
            familyId = this[MemoryCandidateTable.familyId],
            conversationId = this[MemoryCandidateTable.conversationId],
            domainId = this[MemoryCandidateTable.domainId],
            domainName = domain[DomainTable.name],
            memoryType = decodeMemoryType(this[MemoryCandidateTable.memoryType]),
            content = this[MemoryCandidateTable.content],
            summary = this[MemoryCandidateTable.summary],
            subjectMemberId = this[MemoryCandidateTable.subjectMemberId],
            createdBy = this[MemoryCandidateTable.createdBy],
            visibility = this[MemoryCandidateTable.visibility],
            confidence = this[MemoryCandidateTable.confidence],
            sourceConversationMessageId = this[MemoryCandidateTable.sourceConversationMessageId],
            status = CandidateStatus.valueOf(this[MemoryCandidateTable.status]),
            createdAt = this[MemoryCandidateTable.createdAt],
            updatedAt = this[MemoryCandidateTable.updatedAt],
        )
    }

    private fun ResultRow.toMemory(): Memory {
        val domain = DomainTable.selectAll().where { DomainTable.id eq this@toMemory[MemoryTable.domainId] }.single()
        return Memory(
            id = this[MemoryTable.id],
            familyId = this[MemoryTable.familyId],
            domainId = this[MemoryTable.domainId],
            domainName = domain[DomainTable.name],
            memoryType = decodeMemoryType(this[MemoryTable.memoryType]),
            content = this[MemoryTable.content],
            summary = this[MemoryTable.summary],
            subjectMemberId = this[MemoryTable.subjectMemberId],
            createdBy = this[MemoryTable.createdBy],
            visibility = this[MemoryTable.visibility],
            confidence = this[MemoryTable.confidence],
            sourceConversationMessageId = this[MemoryTable.sourceConversationMessageId],
            sourceCandidateId = this[MemoryTable.sourceCandidateId],
            createdAt = this[MemoryTable.createdAt],
            updatedAt = this[MemoryTable.updatedAt],
        )
    }

    private fun decodeMemoryType(value: String): MemoryType =
        json.decodeFromString<MemoryType>(json.encodeToString(value))
}

private const val DEFAULT_FAMILY_NAME = "Default Family"

private val DEFAULT_DOMAIN_NAMES = listOf(
    "HEALTH",
    "SCHOOL",
    "HOME",
    "FINANCE",
    "TRAVEL",
    "SHOPPING",
    "RELATIONSHIP",
    "GENERAL",
)

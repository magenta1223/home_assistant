package com.homeassistant.adapter.outbound.persistence.repo.memory

internal enum class AuditAction {
    CANDIDATE_CREATED,
    CANDIDATE_APPROVED,
    CANDIDATE_REJECTED,
    MEMORY_CREATED,
}

internal data class AuditLogRow(
    val id: Int,
    val action: AuditAction,
    val candidateId: Int?,
    val memoryId: Int?,
)

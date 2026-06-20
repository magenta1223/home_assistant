package com.homeassistant.domain.memory

typealias MemoryType = com.homeassistant.core.memory.MemoryType
typealias CandidateStatus = com.homeassistant.core.memory.CandidateStatus

const val DEFAULT_FAMILY_ID = "default-family"
private const val DEFAULT_FAMILY_NAME = "Default Family"

enum class AuditAction { CANDIDATE_CREATED, CANDIDATE_APPROVED, CANDIDATE_REJECTED, MEMORY_CREATED }

data class MemoryCandidateRow(
    val id: Int,
    val familyId: String,
    val conversationId: String,
    val domainId: Int,
    val domainName: String,
    val memoryType: MemoryType,
    val content: String,
    val summary: String,
    val subjectMemberId: String?,
    val createdBy: String,
    val visibility: String,
    val confidence: Double,
    val sourceConversationMessageId: Int?,
    val status: CandidateStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MemoryRow(
    val id: Int,
    val familyId: String,
    val domainId: Int,
    val domainName: String,
    val memoryType: MemoryType,
    val content: String,
    val summary: String,
    val subjectMemberId: String?,
    val createdBy: String,
    val visibility: String,
    val confidence: Double,
    val sourceConversationMessageId: Int?,
    val sourceCandidateId: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AuditLogRow(
    val id: Int,
    val action: AuditAction,
    val candidateId: Int?,
    val memoryId: Int?,
)

internal val DEFAULT_DOMAIN_NAMES = listOf(
    "HEALTH",
    "SCHOOL",
    "HOME",
    "FINANCE",
    "TRAVEL",
    "SHOPPING",
    "RELATIONSHIP",
    "GENERAL",
)

internal fun defaultFamilyName(): String = DEFAULT_FAMILY_NAME

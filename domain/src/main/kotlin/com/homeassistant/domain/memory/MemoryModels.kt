package com.homeassistant.domain.memory

typealias MemoryType = com.homeassistant.core.memory.MemoryType
typealias CandidateStatus = com.homeassistant.core.memory.CandidateStatus

const val DEFAULT_FAMILY_ID = "default-family"
private const val DEFAULT_FAMILY_NAME = "Default Family"

enum class AuditAction { CANDIDATE_CREATED, CANDIDATE_APPROVED, CANDIDATE_REJECTED, MEMORY_CREATED }

/**
 * Pending or reviewed memory candidate row read from the database.
 *
 * @property id Database id assigned to the candidate.
 * @property familyId Family scope that owns the candidate.
 * @property conversationId Source conversation id associated with the candidate.
 * @property domainId Database id for the candidate's domain.
 * @property domainName Domain name assigned to the candidate.
 * @property memoryType Memory category assigned to the candidate.
 * @property content Full candidate memory content.
 * @property summary Short review-facing summary.
 * @property subjectMemberId Optional family member the memory is about.
 * @property createdBy User id that created the candidate.
 * @property visibility Visibility policy stored for the candidate.
 * @property confidence Confidence score assigned at creation.
 * @property sourceConversationMessageId Optional source message id that produced the candidate.
 * @property status Review status of the candidate.
 * @property createdAt Creation timestamp in epoch milliseconds.
 * @property updatedAt Last update timestamp in epoch milliseconds.
 */
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

/**
 * Confirmed long-term memory row read from the database.
 *
 * @property id Database id assigned to the memory.
 * @property familyId Family scope that owns the memory.
 * @property domainId Database id for the memory's domain.
 * @property domainName Domain name assigned to the memory.
 * @property memoryType Memory category assigned to the memory.
 * @property content Full memory content.
 * @property summary Short search- and review-facing summary.
 * @property subjectMemberId Optional family member the memory is about.
 * @property createdBy User id that created the source candidate.
 * @property visibility Visibility policy stored for the memory.
 * @property confidence Confidence score carried from the source candidate.
 * @property sourceConversationMessageId Optional source message id that produced the memory.
 * @property sourceCandidateId Candidate id approved into this memory.
 * @property createdAt Creation timestamp in epoch milliseconds.
 * @property updatedAt Last update timestamp in epoch milliseconds.
 */
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

/**
 * Audit log row for memory candidate and memory lifecycle events.
 *
 * @property id Database id assigned to the audit event.
 * @property action Lifecycle action recorded by the event.
 * @property candidateId Optional candidate id associated with the event.
 * @property memoryId Optional memory id associated with the event.
 */
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

package com.homeassistant.domain.memory

import com.homeassistant.domain.memory.CandidateStatus
import com.homeassistant.domain.memory.MemoryType

const val DEFAULT_FAMILY_ID = "default-family"

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

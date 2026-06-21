package com.homeassistant.domain.memory

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.memory.MemoryCandidateRow
import com.homeassistant.datamodel.memory.MemoryRow

interface MemoryStore {
    fun createCandidate(
        userId: UserId,
        conversationId: String,
        domainName: String,
        memoryType: MemoryType,
        content: String,
        summary: String,
        confidence: Double,
        sourceConversationMessageId: Int?,
        subjectMemberId: String? = null,
        visibility: String = "FAMILY",
    ): Int

    fun listPending(userId: UserId, conversationId: String): List<MemoryCandidateRow>
    fun getCandidate(id: Int): MemoryCandidateRow?
    fun approveCandidate(userId: UserId, candidateId: Int): MemoryRow
    fun rejectCandidate(userId: UserId, candidateId: Int)
    fun getMemory(id: Int): MemoryRow?
    fun listMemories(ids: List<Int>? = null): List<MemoryRow>
}

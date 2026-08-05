package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryType

interface MemoryCommandStore {
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

    fun approveCandidate(userId: UserId, candidateId: Int): Memory
    fun rejectCandidate(userId: UserId, candidateId: Int)
}

interface MemoryQueryStore {
    fun listPending(userId: UserId, conversationId: String): List<MemoryCandidate>
    fun getCandidate(id: Int): MemoryCandidate?
    fun getMemory(id: Int): Memory?
    fun listMemories(ids: List<Int>? = null): List<Memory>
}

interface MemoryStore : MemoryCommandStore, MemoryQueryStore

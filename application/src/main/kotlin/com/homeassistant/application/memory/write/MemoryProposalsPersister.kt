package com.homeassistant.application.memory.write

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryProposal

/** Persists flat memory proposals immediately without a review stage. */
class MemoryProposalsPersister(
    private val memoryWriter: MemoryWriter,
    private val memoryIndexWriter: SemanticMemoryIndexWriter,
) {

    fun persist(userId: UserId, proposals: List<MemoryProposal>): List<Memory> {
        if (proposals.isEmpty()) return emptyList()

        val savedMemories = proposals
            .distinctBy { it.content to it.evidenceIds.toSet() }
            .map { memoryWriter.write(it, userId) }
        savedMemories.forEach {
            memoryIndexWriter.upsert(it)
        }
        return savedMemories
    }
}

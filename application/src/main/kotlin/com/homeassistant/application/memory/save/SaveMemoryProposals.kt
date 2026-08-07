package com.homeassistant.application.memory.save

import com.homeassistant.application.memory.index.SemanticMemoryIndexWriter
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryProposal

/** Persists flat memory proposals immediately without a review stage. */
class SaveMemoryProposals(
    private val memoryCreator: MemoryCreator,
    memoryIndexWriter: SemanticMemoryIndexWriter,
    indexingOutbox: IndexingOutboxStore,
    memoryReader: MemoryReader,
) : MemoryProposalSaver {
    private val memoryIndexing = MemoryIndexingCoordinator(
        memoryReader,
        memoryIndexWriter,
        indexingOutbox,
    )

    override fun save(userId: UserId, proposals: List<MemoryProposal>): List<Memory> {
        if (proposals.isEmpty()) return emptyList()

        val savedMemories = proposals
            .distinctBy { it.content to it.evidenceIds.toSet() }
            .map { memoryCreator.create(it, userId) }
        savedMemories.forEach(memoryIndexing::index)
        memoryIndexing.retryPending(savedMemories.mapTo(mutableSetOf()) { it.id })
        return savedMemories
    }
}

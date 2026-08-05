package com.homeassistant.application.memory

import com.homeassistant.application.memory.approve.ApproveMemoryCandidate
import com.homeassistant.application.memory.create.CreateMemoryCandidate
import com.homeassistant.application.memory.list.ListPendingMemoryCandidates
import com.homeassistant.application.memory.reject.RejectMemoryCandidate
import com.homeassistant.application.memory.search.SearchMemories
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.MemoryStore
import com.homeassistant.domain.memory.VectorStore

data class MemoryUseCases(
    val createCandidate: CreateMemoryCandidate,
    val listPendingCandidates: ListPendingMemoryCandidates,
    val approveCandidate: ApproveMemoryCandidate,
    val rejectCandidate: RejectMemoryCandidate,
    val searchMemories: SearchMemories,
)

object MemoryUseCasesFactory {
    fun create(
        memoryStore: MemoryStore,
        embeddingService: EmbeddingService,
        vectorStore: VectorStore,
        indexingOutbox: IndexingOutboxStore,
    ): MemoryUseCases =
        MemoryUseCases(
            createCandidate = CreateMemoryCandidate(memoryStore),
            listPendingCandidates = ListPendingMemoryCandidates(memoryStore),
            approveCandidate = ApproveMemoryCandidate(
                memoryStore,
                embeddingService,
                vectorStore,
                indexingOutbox,
            ),
            rejectCandidate = RejectMemoryCandidate(memoryStore),
            searchMemories = SearchMemories(memoryStore, embeddingService, vectorStore),
        )
}

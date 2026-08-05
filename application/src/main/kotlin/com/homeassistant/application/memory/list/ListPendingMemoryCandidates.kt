package com.homeassistant.application.memory.list

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCandidateRow
import com.homeassistant.domain.memory.MemoryQueryStore

data class ListPendingMemoryCandidatesInput(
    val userId: UserId,
    val conversationId: String,
)

data class ListPendingMemoryCandidatesOutput(
    val candidates: List<MemoryCandidateRow>,
)

class ListPendingMemoryCandidates(
    private val memoryStore: MemoryQueryStore,
) {
    fun execute(input: ListPendingMemoryCandidatesInput): ListPendingMemoryCandidatesOutput =
        ListPendingMemoryCandidatesOutput(
            memoryStore.listPending(input.userId, input.conversationId),
        )
}

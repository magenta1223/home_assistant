package com.homeassistant.application.memory.reject

import com.homeassistant.core.identity.UserId
import com.homeassistant.domain.memory.MemoryCommandStore

data class RejectMemoryCandidateInput(
    val userId: UserId,
    val candidateId: Int,
)

class RejectMemoryCandidate(
    private val memoryStore: MemoryCommandStore,
) {
    fun execute(input: RejectMemoryCandidateInput) {
        memoryStore.rejectCandidate(input.userId, input.candidateId)
    }
}

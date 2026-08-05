package com.homeassistant.application.memory.approve

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.memory.*
import org.slf4j.LoggerFactory

data class ApproveMemoryCandidateInput(
    val userId: UserId,
    val candidateId: Int,
)

data class ApproveMemoryCandidateOutput(
    val memory: Memory,
    val indexed: Boolean,
)

class ApproveMemoryCandidate(
    private val memoryStore: MemoryStore,
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val indexingOutbox: IndexingOutboxStore,
) {
    fun execute(input: ApproveMemoryCandidateInput): ApproveMemoryCandidateOutput {
        val memory = memoryStore.approveCandidate(input.userId, input.candidateId)
        val indexed = index(memory)
        retryPending(memory.id)
        return ApproveMemoryCandidateOutput(memory, indexed)
    }

    private fun index(memory: Memory): Boolean =
        try {
            vectorStore.upsert(memory.toVectorPoint())
            indexingOutbox.markIndexed(IndexTargetType.MEMORY, memory.id)
            true
        } catch (error: Exception) {
            log.warn("Memory vector indexing deferred memoryId=${memory.id}", error)
            runCatching {
                indexingOutbox.markFailed(
                    IndexTargetType.MEMORY,
                    memory.id,
                    error.message ?: error::class.simpleName.orEmpty(),
                )
            }
            false
        }

    private fun retryPending(currentMemoryId: Int) {
        runCatching {
            indexingOutbox.pending(IndexTargetType.MEMORY)
                .asSequence()
                .filter { it != currentMemoryId }
                .mapNotNull(memoryStore::getMemory)
                .forEach(::index)
        }.onFailure { error ->
            log.warn("Failed to dispatch pending memory indexes", error)
        }
    }

    private fun Memory.toVectorPoint() =
        VectorPoint(
            memoryId = id,
            vector = embeddingService.embed("passage: $summary\n$content"),
            payload = mapOf(
                "familyId" to familyId,
                "memoryId" to id.toString(),
                "memoryType" to memoryType.code,
                "domain" to domainName,
                "memberId" to (subjectMemberId ?: ""),
                "createdBy" to createdBy,
            ),
            numericPayload = mapOf("createdAt" to createdAt),
        )
}

private val log = LoggerFactory.getLogger(ApproveMemoryCandidate::class.java)

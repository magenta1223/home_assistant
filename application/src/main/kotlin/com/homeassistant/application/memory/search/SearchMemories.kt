package com.homeassistant.application.memory.search

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.memory.*

data class SearchMemoriesInput(
    val userId: UserId,
    val query: String,
    val memoryType: MemoryType? = null,
    val domain: String? = null,
    val memberId: String? = null,
    val createdAfter: Long? = null,
    val createdBefore: Long? = null,
    val limit: Int = 5,
)

data class MemorySearchMatch(
    val memory: MemoryRow,
    val score: Double,
)

data class SearchMemoriesOutput(
    val matches: List<MemorySearchMatch>,
)

class SearchMemories(
    private val memoryStore: MemoryQueryStore,
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
) {
    fun execute(input: SearchMemoriesInput): SearchMemoriesOutput {
        val filter = MemorySearchFilter(
            createdBy = input.userId.value,
            memoryType = input.memoryType,
            domain = input.domain?.uppercase(),
            memberId = input.memberId,
            createdAfter = input.createdAfter,
            createdBefore = input.createdBefore,
        )
        val results = vectorStore.search(
            embeddingService.embed("query: ${input.query}"),
            filter,
            input.limit,
        )
        val byId = memoryStore.listMemories(results.map { it.memoryId }).associateBy { it.id }
        return SearchMemoriesOutput(
            results.mapNotNull { result ->
                byId[result.memoryId]?.let { MemorySearchMatch(it, result.score) }
            },
        )
    }
}

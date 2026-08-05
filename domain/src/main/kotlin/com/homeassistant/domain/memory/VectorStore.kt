package com.homeassistant.domain.memory

import com.homeassistant.domain.memory.MemoryType

/**
 * Filters applied to vector memory search.
 *
 * @property familyId Family scope to search within.
 * @property createdBy User who owns the memory.
 * @property memoryType Optional memory category filter.
 * @property domain Optional domain name filter.
 * @property memberId Optional subject member filter.
 * @property createdAfter Optional lower creation timestamp bound in epoch milliseconds.
 * @property createdBefore Optional upper creation timestamp bound in epoch milliseconds.
 */
data class MemorySearchFilter(
    val familyId: String = DEFAULT_FAMILY_ID,
    val createdBy: String? = null,
    val memoryType: MemoryType? = null,
    val domain: String? = null,
    val memberId: String? = null,
    val createdAfter: Long? = null,
    val createdBefore: Long? = null,
)

/**
 * Vector-store point representing one confirmed memory.
 *
 * @property memoryId Confirmed memory id used as the vector point id.
 * @property vector Embedding vector for the memory text.
 * @property payload String metadata stored with the vector point.
 * @property numericPayload Numeric metadata stored with the vector point.
 */
data class VectorPoint(
    val memoryId: Int,
    val vector: List<Float>,
    val payload: Map<String, String>,
    val numericPayload: Map<String, Long> = emptyMap(),
)

/**
 * Vector search hit mapped back to a memory id.
 *
 * @property memoryId Confirmed memory id matched by the vector search.
 * @property score Similarity score returned by the vector store.
 */
data class VectorSearchResult(val memoryId: Int, val score: Double)

interface VectorStore {
    fun upsert(point: VectorPoint)
    fun search(vector: List<Float>, filter: MemorySearchFilter, limit: Int): List<VectorSearchResult>
}

data class PayloadVectorPoint(
    val id: Int,
    val vector: List<Float>,
    val payload: Map<String, String>,
    val numericPayload: Map<String, Long> = emptyMap(),
)

data class PayloadVectorSearchFilter(
    val must: Map<String, String> = emptyMap(),
    val ranges: Map<String, NumericRange> = emptyMap(),
)

data class NumericRange(
    val gte: Long? = null,
    val lte: Long? = null,
)

data class PayloadVectorSearchResult(
    val id: Int,
    val score: Double,
    val payload: Map<String, String>,
)

interface PayloadVectorStore {
    fun upsert(point: PayloadVectorPoint)
    fun search(vector: List<Float>, filter: PayloadVectorSearchFilter, limit: Int): List<PayloadVectorSearchResult>
}

interface EmbeddingService {
    fun embed(text: String): List<Float>
}

internal class DeterministicEmbeddingService(model: String) : EmbeddingService {
    private val modelName = model.ifBlank { error("EMBEDDING_MODEL must not be blank") }

    override fun embed(text: String): List<Float> {
        val seed = "$modelName:$text"
        return List(16) { index ->
            val byte = seed.getOrElse(index % seed.length) { '0' }.code
            ((byte % 31) / 31.0f)
        }
    }
}

object DomainEmbeddingServiceFactory {
    fun deterministic(model: String): EmbeddingService =
        DeterministicEmbeddingService(model)
}

package com.homeassistant.domain.memory

data class MemorySearchFilter(
    val familyId: String = DEFAULT_FAMILY_ID,
    val memoryKind: MemoryKind? = null,
    val memorySubtype: String? = null,
    val domain: String? = null,
    val memberId: String? = null,
    val createdAfter: Long? = null,
    val createdBefore: Long? = null,
)

data class VectorPoint(
    val memoryId: Int,
    val vector: List<Float>,
    val payload: Map<String, String>,
)

data class VectorSearchResult(val memoryId: Int, val score: Double)

interface VectorStore {
    fun upsert(point: VectorPoint)
    fun search(vector: List<Float>, filter: MemorySearchFilter, limit: Int): List<VectorSearchResult>
}

interface EmbeddingService {
    fun embed(text: String): List<Float>
}

class DeterministicEmbeddingService(model: String) : EmbeddingService {
    private val modelName = model.ifBlank { error("EMBEDDING_MODEL must not be blank") }

    override fun embed(text: String): List<Float> {
        val seed = "$modelName:$text"
        return List(16) { index ->
            val byte = seed.getOrElse(index % seed.length) { '0' }.code
            ((byte % 31) / 31.0f)
        }
    }
}

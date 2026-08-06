package com.homeassistant.domain.memory

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

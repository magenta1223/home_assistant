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

package com.homeassistant.adapter.outbound.vector

data class VectorPoint(
    val id: Int,
    val vector: List<Float>,
    val payload: Map<String, String>,
    val numericPayload: Map<String, Long> = emptyMap(),
)

data class VectorSearchFilter(
    val must: Map<String, String> = emptyMap(),
    val ranges: Map<String, NumericRange> = emptyMap(),
)

data class NumericRange(
    val gte: Long? = null,
    val lte: Long? = null,
)

data class VectorSearchResult(
    val id: Int,
    val score: Double,
    val payload: Map<String, String>,
)

interface VectorStore {
    fun upsert(point: VectorPoint)
    fun search(vector: List<Float>, filter: VectorSearchFilter, limit: Int): List<VectorSearchResult>
}

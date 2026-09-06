package com.homeassistant.adapter.outbound.vector.qdrant

import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.adapter.outbound.vector.VectorSearchResult
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.common.json.JsonSerializer.decodeFromString
import com.homeassistant.common.json.JsonSerializer.encodeToString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal class QdrantVectorStore(
    private val collection: String,
    private val transport: QdrantTransport,
) : VectorStore {
    @Volatile private var collectionReady = false

    override fun upsert(point: VectorPoint) {
        ensureCollection(point.vector.size)
        transport.request("PUT", "/collections/$collection/points?wait=true", qdrantUpsertBody(point))
    }

    override fun search(
        vector: List<Float>,
        filter: VectorSearchFilter,
        limit: Int,
    ): List<VectorSearchResult> {
        ensureCollection(vector.size)
        val body = qdrantSearchBody(vector, filter, limit)
        val response = transport.request("POST", "/collections/$collection/points/search", body)
        return response
            .decodeFromString<QdrantSearchResponse>()
            .result
            .map { hit ->
                VectorSearchResult(
                    hit.id,
                    hit.score,
                    hit.payload.mapValues { (_, value) -> value.jsonPrimitive.content },
                )
            }
    }

    private fun ensureCollection(vectorSize: Int) {
        if (collectionReady) return
        synchronized(this) {
            if (collectionReady) return
            val body = QdrantCreateCollectionRequest(
                vectors = QdrantVectorParams(vectorSize, QdrantDistance.COSINE),
            ).encodeToString()
            if (!transport.exists("/collections/$collection")) {
                transport.request("PUT", "/collections/$collection", body)
            }
            collectionReady = true
        }
    }

    /**
     * Qdrant search response body.
     *
     * @property result Search hits returned by Qdrant.
     */
    @Serializable
    private data class QdrantSearchResponse(val result: List<QdrantHit> = emptyList())

    /**
     * Qdrant search hit.
     *
     * @property id Point id returned by Qdrant.
     * @property score Similarity score returned by Qdrant.
     * @property payload String metadata returned with the hit.
     */
    @Serializable
    private data class QdrantHit(
        val id: Int,
        val score: Double,
        val payload: Map<String, JsonPrimitive> = emptyMap(),
    )
}

object QdrantVectorStoreFactory {
    fun create(
        baseUrl: String,
        collection: String,
    ): VectorStore =
        QdrantVectorStore(collection, QdrantTransportFactory.http(baseUrl))
}

internal fun qdrantUpsertBody(point: VectorPoint): String =
    QdrantUpsertRequest(
        points = listOf(
            QdrantPoint(
                id = point.id,
                vector = point.vector,
                payload = point.payload.mapValues { (_, value) -> JsonPrimitive(value) } +
                    point.numericPayload.mapValues { (_, value) -> JsonPrimitive(value) },
            ),
        ),
    ).encodeToString()

internal fun qdrantSearchBody(
    vector: List<Float>,
    filter: VectorSearchFilter,
    limit: Int,
): String =
    QdrantSearchRequest(
        vector = vector,
        limit = limit,
        filter = (
            filter.must.map { (key, value) -> QdrantCondition.match(key, value) } +
                filter.ranges.map { (key, range) -> QdrantCondition.range(key, range.gte, range.lte) } +
                if (filter.ids.isNotEmpty()) listOf(QdrantCondition.hasIds(filter.ids.sorted())) else emptyList()
            )
            .takeIf(List<QdrantCondition>::isNotEmpty)
            ?.let(::QdrantFilter),
    ).encodeToString()

@Serializable
private data class QdrantCreateCollectionRequest(val vectors: QdrantVectorParams)

@Serializable
private data class QdrantVectorParams(
    val size: Int,
    val distance: QdrantDistance,
)

@Serializable
private enum class QdrantDistance {
    @SerialName("Cosine") COSINE,
}

@Serializable
private data class QdrantUpsertRequest(val points: List<QdrantPoint>)

@Serializable
private data class QdrantPoint(
    val id: Int,
    val vector: List<Float>,
    val payload: Map<String, JsonPrimitive>,
)

@Serializable
private data class QdrantSearchRequest(
    val vector: List<Float>,
    val limit: Int,
    @SerialName("with_payload") val withPayload: Boolean = true,
    val filter: QdrantFilter? = null,
)

@Serializable
private data class QdrantFilter(val must: List<QdrantCondition>)

@Serializable
private class QdrantCondition private constructor(
    val key: String? = null,
    val match: QdrantMatch? = null,
    val range: QdrantRange? = null,
    @SerialName("has_id") val hasId: List<Int>? = null,
) {
    init {
        require(listOfNotNull(match, range, hasId).size == 1) { "Qdrant condition must have exactly one operator" }
        require((key == null) == (hasId != null)) { "Only has_id conditions omit the key" }
    }

    companion object {
        fun match(key: String, value: String): QdrantCondition =
            QdrantCondition(key = key, match = QdrantMatch(value))

        fun range(key: String, gte: Long?, lte: Long?): QdrantCondition =
            QdrantCondition(key = key, range = QdrantRange(gte, lte))

        fun hasIds(ids: List<Int>): QdrantCondition = QdrantCondition(hasId = ids)
    }
}

@Serializable
private data class QdrantMatch(val value: String)

@Serializable
private data class QdrantRange(
    val gte: Long? = null,
    val lte: Long? = null,
)

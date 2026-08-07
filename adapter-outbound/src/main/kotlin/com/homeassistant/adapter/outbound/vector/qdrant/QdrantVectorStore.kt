package com.homeassistant.adapter.outbound.vector.qdrant

import com.homeassistant.common.json.JsonSerializer.decodeFromString
import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.adapter.outbound.vector.VectorSearchResult
import com.homeassistant.adapter.outbound.vector.VectorStore
import kotlinx.serialization.json.*

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
            val body = buildJsonObject {
                put("vectors", buildJsonObject {
                    put("size", vectorSize)
                    put("distance", "Cosine")
                })
            }.toString()
            transport.request("PUT", "/collections/$collection", body)
            collectionReady = true
        }
    }

    /**
     * Qdrant search response body.
     *
     * @property result Search hits returned by Qdrant.
     */
    @kotlinx.serialization.Serializable
    private data class QdrantSearchResponse(val result: List<QdrantHit> = emptyList())

    /**
     * Qdrant search hit.
     *
     * @property id Point id returned by Qdrant.
     * @property score Similarity score returned by Qdrant.
     * @property payload String metadata returned with the hit.
     */
    @kotlinx.serialization.Serializable
    private data class QdrantHit(val id: Int, val score: Double, val payload: JsonObject = buildJsonObject {})
}

object QdrantVectorStoreFactory {
    fun create(
        baseUrl: String,
        collection: String,
    ): VectorStore =
        QdrantVectorStore(collection, QdrantTransportFactory.http(baseUrl))
}

internal fun qdrantUpsertBody(point: VectorPoint): String =
    buildJsonObject {
        put("points", buildJsonArray {
            add(buildJsonObject {
                put("id", point.id)
                put("vector", JsonArray(point.vector.map(::JsonPrimitive)))
                put("payload", buildJsonObject {
                    point.payload.forEach { (key, value) -> put(key, value) }
                    point.numericPayload.forEach { (key, value) -> put(key, value) }
                })
            })
        })
    }.toString()

internal fun qdrantSearchBody(
    vector: List<Float>,
    filter: VectorSearchFilter,
    limit: Int,
): String =
    buildJsonObject {
        put("vector", JsonArray(vector.map(::JsonPrimitive)))
        put("limit", limit)
        put("with_payload", true)
        val conditions = filter.must.map { (key, value) ->
            buildJsonObject {
                put("key", key)
                put("match", buildJsonObject { put("value", value) })
            }
        } + filter.ranges.map { (key, range) ->
            buildJsonObject {
                put("key", key)
                put("range", buildJsonObject {
                    range.gte?.let { put("gte", it) }
                    range.lte?.let { put("lte", it) }
                })
            }
        } + if (filter.ids.isNotEmpty()) {
            listOf(
                buildJsonObject {
                    put("has_id", JsonArray(filter.ids.sorted().map(::JsonPrimitive)))
                },
            )
        } else {
            emptyList()
        }
        if (conditions.isNotEmpty()) {
            put("filter", buildJsonObject { put("must", JsonArray(conditions)) })
        }
    }.toString()

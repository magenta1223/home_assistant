package com.homeassistant.domain.memory

import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import com.homeassistant.core.utils.JsonSerializer.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class QdrantVectorStore(
    private val baseUrl: String,
    private val collection: String,
) : VectorStore, PayloadVectorStore {
    private val client = HttpClient.newHttpClient()
    @Volatile private var collectionReady = false

    override fun upsert(point: VectorPoint) {
        upsert(
            PayloadVectorPoint(
                id = point.memoryId,
                vector = point.vector,
                payload = point.payload,
            ),
        )
    }

    override fun upsert(point: PayloadVectorPoint) {
        ensureCollection(point.vector.size)
        val body = QdrantUpsertRequest(
            points = listOf(
                QdrantPoint(
                    id = point.id,
                    vector = point.vector,
                    payload = point.payload,
                ),
            )
        )
        request("PUT", "/collections/$collection/points?wait=true", body.encodeToString())
    }

    override fun search(vector: List<Float>, filter: MemorySearchFilter, limit: Int): List<VectorSearchResult> {
        val must = buildMap {
            put("familyId", filter.familyId)
            filter.memoryType?.let { put("memoryType", it.code) }
            filter.domain?.let { put("domain", it.uppercase()) }
            filter.memberId?.let { put("memberId", it) }
        }
        return search(vector, PayloadVectorSearchFilter(must), limit)
            .map { hit ->
                val memoryId = hit.payload["memoryId"]?.toIntOrNull() ?: hit.id
                VectorSearchResult(memoryId, hit.score)
            }
    }

    override fun search(
        vector: List<Float>,
        filter: PayloadVectorSearchFilter,
        limit: Int,
    ): List<PayloadVectorSearchResult> {
        ensureCollection(vector.size)
        val body = buildJsonObject {
            put("vector", JsonArray(vector.map { JsonPrimitive(it) }))
            put("limit", limit)
            put("with_payload", true)
            val must = filter.must.map { (key, value) -> match(key, value) }
            if (must.isNotEmpty()) put("filter", buildJsonObject { put("must", JsonArray(must)) })
        }.toString()
        val response = request("POST", "/collections/$collection/points/search", body)
        return response
            .decodeFromString<QdrantSearchResponse>()
            .result
            .map { hit ->
                PayloadVectorSearchResult(hit.id, hit.score, hit.payload)
            }
    }

    private fun match(key: String, value: String): JsonObject = buildJsonObject {
        put("key", key)
        put("match", buildJsonObject { put("value", value) })
    }

    private fun request(method: String, path: String, body: String): String {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Qdrant request failed status=${response.statusCode()} body=${response.body()}")
        }
        return response.body()
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
            request("PUT", "/collections/$collection", body)
            collectionReady = true
        }
    }

    /**
     * Qdrant upsert request body.
     *
     * @property points Vector points to insert or replace.
     */
    @Serializable private data class QdrantUpsertRequest(val points: List<QdrantPoint>)

    /**
     * Qdrant vector point payload.
     *
     * @property id Point id stored in Qdrant.
     * @property vector Embedding vector stored for the point.
     * @property payload String metadata stored with the point.
     */
    @Serializable private data class QdrantPoint(val id: Int, val vector: List<Float>, val payload: Map<String, String>)

    /**
     * Qdrant search response body.
     *
     * @property result Search hits returned by Qdrant.
     */
    @Serializable private data class QdrantSearchResponse(val result: List<QdrantHit> = emptyList())

    /**
     * Qdrant search hit.
     *
     * @property id Point id returned by Qdrant.
     * @property score Similarity score returned by Qdrant.
     * @property payload String metadata returned with the hit.
     */
    @Serializable private data class QdrantHit(val id: Int, val score: Double, val payload: Map<String, String> = emptyMap())
}

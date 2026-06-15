package com.homeassistant.domain.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class QdrantVectorStore(
    private val baseUrl: String,
    private val collection: String,
) : VectorStore {
    private val client = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    @Volatile private var collectionReady = false

    override fun upsert(point: VectorPoint) {
        ensureCollection(point.vector.size)
        val body = json.encodeToString(
            QdrantUpsertRequest(
                points = listOf(
                    QdrantPoint(
                        id = point.memoryId,
                        vector = point.vector,
                        payload = point.payload,
                    ),
                ),
            ),
        )
        request("PUT", "/collections/$collection/points?wait=true", body)
    }

    override fun search(vector: List<Float>, filter: MemorySearchFilter, limit: Int): List<VectorSearchResult> {
        ensureCollection(vector.size)
        val body = buildJsonObject {
            put("vector", JsonArray(vector.map { JsonPrimitive(it) }))
            put("limit", limit)
            put("with_payload", true)
            val must = buildList {
                add(match("familyId", filter.familyId))
                filter.memoryKind?.let { add(match("memoryKind", it.name)) }
                filter.memorySubtype?.let { add(match("memorySubtype", it.uppercase())) }
                filter.domain?.let { add(match("domain", it.uppercase())) }
                filter.memberId?.let { add(match("memberId", it)) }
            }
            if (must.isNotEmpty()) put("filter", buildJsonObject { put("must", JsonArray(must)) })
        }.toString()
        val response = request("POST", "/collections/$collection/points/search", body)
        return json.decodeFromString<QdrantSearchResponse>(response).result.mapNotNull { hit ->
            val memoryId = hit.payload["memoryId"]?.toIntOrNull() ?: hit.id
            VectorSearchResult(memoryId, hit.score)
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

    @Serializable private data class QdrantUpsertRequest(val points: List<QdrantPoint>)
    @Serializable private data class QdrantPoint(val id: Int, val vector: List<Float>, val payload: Map<String, String>)
    @Serializable private data class QdrantSearchResponse(val result: List<QdrantHit> = emptyList())
    @Serializable private data class QdrantHit(val id: Int, val score: Double, val payload: Map<String, String> = emptyMap())
}

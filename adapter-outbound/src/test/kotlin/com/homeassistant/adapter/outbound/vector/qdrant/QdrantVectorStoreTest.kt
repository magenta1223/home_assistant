package com.homeassistant.adapter.outbound.vector.qdrant

import com.homeassistant.adapter.outbound.vector.NumericRange
import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.common.json.JsonSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class QdrantVectorStoreTest {
    @Test
    fun `reuses a collection that already exists after Qdrant restarts`() {
        val transport = RecordingTransport(collectionExists = true)
        val store = QdrantVectorStore("memories", transport)

        store.upsert(point())

        assertEquals(
            listOf(
                "EXISTS /collections/memories",
                "PUT /collections/memories/points?wait=true",
            ),
            transport.operations,
        )
    }

    @Test
    fun `creates a collection before the first upsert when it is absent`() {
        val transport = RecordingTransport(collectionExists = false)
        val store = QdrantVectorStore("memories", transport)

        store.upsert(point())

        assertEquals(
            listOf(
                "EXISTS /collections/memories",
                "PUT /collections/memories",
                "PUT /collections/memories/points?wait=true",
            ),
            transport.operations,
        )
        assertJsonEquals(
            """{"vectors":{"size":2,"distance":"Cosine"}}""",
            transport.requests.first { it.path == "/collections/memories" }.body,
        )
    }

    @Test
    fun `serializes an upsert request from typed models`() {
        val point = point().copy(numericPayload = mapOf("sourceRecordId" to 42L))

        val body = qdrantUpsertBody(point)

        assertJsonEquals(
            """{
              "points":[{
                "id":1,
                "vector":[0.1,0.2],
                "payload":{"kind":"memory","sourceRecordId":42}
              }]
            }""".trimIndent(),
            body,
        )
    }

    @Test
    fun `serializes match range and id search conditions`() {
        val body = qdrantSearchBody(
            vector = listOf(0.1f, 0.2f),
            filter = VectorSearchFilter(
                must = linkedMapOf("visibility" to "PUBLIC"),
                ranges = linkedMapOf("sourceRecordId" to NumericRange(gte = 10, lte = 20)),
                ids = setOf(3, 1),
            ),
            limit = 5,
        )

        assertJsonEquals(
            """{
              "vector":[0.1,0.2],
              "limit":5,
              "with_payload":true,
              "filter":{"must":[
                {"key":"visibility","match":{"value":"PUBLIC"}},
                {"key":"sourceRecordId","range":{"gte":10,"lte":20}},
                {"has_id":[1,3]}
              ]}
            }""".trimIndent(),
            body,
        )
    }

    @Test
    fun `omits the filter when no search conditions exist`() {
        val body = qdrantSearchBody(
            vector = listOf(0.1f),
            filter = VectorSearchFilter(),
            limit = 1,
        )

        assertJsonEquals(
            """{"vector":[0.1],"limit":1,"with_payload":true}""",
            body,
        )
    }

    private fun point() = VectorPoint(
        id = 1,
        vector = listOf(0.1f, 0.2f),
        payload = mapOf("kind" to "memory"),
    )

    private class RecordingTransport(
        private val collectionExists: Boolean,
    ) : QdrantTransport {
        val operations = mutableListOf<String>()
        val requests = mutableListOf<RecordedRequest>()

        override fun exists(path: String): Boolean {
            operations += "EXISTS $path"
            return collectionExists
        }

        override fun request(method: String, path: String, body: String): String {
            operations += "$method $path"
            requests += RecordedRequest(method, path, body)
            return "{}"
        }
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private fun assertJsonEquals(expected: String, actual: String) {
        assertEquals(
            Json.parseToJsonElement(expected),
            JsonSerializer.json.parseToJsonElement(actual),
        )
    }
}

package com.homeassistant.adapter.outbound.vector.qdrant

import com.homeassistant.adapter.outbound.vector.VectorPoint
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

        override fun exists(path: String): Boolean {
            operations += "EXISTS $path"
            return collectionExists
        }

        override fun request(method: String, path: String, body: String): String {
            operations += "$method $path"
            return "{}"
        }
    }
}

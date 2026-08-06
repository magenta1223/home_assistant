package com.homeassistant.adapter.outbound.vector.qdrant

import com.homeassistant.adapter.outbound.vector.NumericRange
import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class QdrantVectorStoreTest {
    @Test
    fun `upsert body stores createdAt as a number`() {
        val body = qdrantUpsertBody(
            VectorPoint(
                id = 1,
                vector = listOf(0.1f),
                payload = mapOf("createdBy" to "dad"),
                numericPayload = mapOf("createdAt" to 123L),
            ),
        )

        val payload = Json.parseToJsonElement(body)
            .jsonObject.getValue("points").jsonArray.single()
            .jsonObject.getValue("payload").jsonObject

        assertEquals("dad", payload.getValue("createdBy").jsonPrimitive.content)
        assertEquals(123L, payload.getValue("createdAt").jsonPrimitive.long)
    }

    @Test
    fun `search body includes match and numeric range conditions`() {
        val body = qdrantSearchBody(
            vector = listOf(0.1f),
            filter = VectorSearchFilter(
                must = mapOf("createdBy" to "dad"),
                ranges = mapOf("createdAt" to NumericRange(gte = 100L, lte = 200L)),
            ),
            limit = 5,
        )

        val must = Json.parseToJsonElement(body)
            .jsonObject.getValue("filter").jsonObject
            .getValue("must").jsonArray

        assertEquals("dad", must[0].jsonObject.getValue("match").jsonObject.getValue("value").jsonPrimitive.content)
        val range = must[1].jsonObject.getValue("range").jsonObject
        assertEquals(100L, range.getValue("gte").jsonPrimitive.long)
        assertEquals(200L, range.getValue("lte").jsonPrimitive.long)
    }
}

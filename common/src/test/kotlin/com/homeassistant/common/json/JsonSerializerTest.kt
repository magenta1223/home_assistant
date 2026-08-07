package com.homeassistant.common.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.homeassistant.common.json.JsonSerializer.toJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonSerializerTest {
    @Serializable
    private data class Payload(val name: String, val optional: String? = null)

    @Test
    fun `omits explicit nulls when encoding`() {
        val encoded = JsonSerializer.json.encodeToString(Payload(name = "home"))

        assertFalse(encoded.contains("optional"))
    }

    @Test
    fun `ignores unknown keys when decoding`() {
        val decoded = JsonSerializer.json.decodeFromString<Payload>(
            """{"name":"home","unknown":"value"}""",
        )

        assertEquals(Payload(name = "home"), decoded)
    }

    @Test
    fun `parses lenient json with trailing comma`() {
        val decoded = JsonSerializer.json.parseToJsonElement(
            """{"name":"home",}""",
        ).jsonObject

        assertEquals("home", decoded.getValue("name").toString().trim('"'))
    }

    @Test
    fun `encodes nested untyped values as json elements`() {
        val encoded = JsonSerializer.json.encodeToString(
            mapOf(
                "type" to "section",
                "blocks" to listOf(mapOf("text" to mapOf("text" to "hello"))),
            ).let { it.toJsonElement() },
        )

        val decoded = JsonSerializer.json.parseToJsonElement(encoded).jsonObject
        assertEquals("section", decoded.getValue("type").jsonPrimitive.content)
        assertEquals(
            "hello",
            decoded.getValue("blocks").jsonArray
                .single().jsonObject.getValue("text").jsonObject
                .getValue("text").jsonPrimitive.content,
        )
    }
}

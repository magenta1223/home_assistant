package com.homeassistant.core.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
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
}

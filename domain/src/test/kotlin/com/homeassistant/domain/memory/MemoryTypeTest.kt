package com.homeassistant.domain.memory

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryTypeTest {

    @Test
    fun `serializes memory type as its code`() {
        assertEquals(""""STATE"""", Json.encodeToString(MemoryType.STATE))
        assertEquals(""""EVENT"""", Json.encodeToString(MemoryType.EVENT))
        assertEquals(""""CHECKLIST"""", Json.encodeToString(MemoryType.CHECKLIST))
    }

    @Test
    fun `deserializes memory type code to concrete implementation`() {
        assertEquals(MemoryType.STATE, Json.decodeFromString(""""STATE""""))
        assertEquals(MemoryType.EVENT, Json.decodeFromString(""""EVENT""""))
        assertEquals(MemoryType.CHECKLIST, Json.decodeFromString(""""CHECKLIST""""))
    }

    @Test
    fun `memory type entries expose group code and code`() {
        assertEquals("SEMANTIC", MemoryType.STATE.groupCode)
        assertEquals("EPISODIC", MemoryType.EVENT.groupCode)
        assertEquals("PROCEDURAL", MemoryType.CHECKLIST.groupCode)
    }

    @Test
    fun `deserialization rejects unknown memory type`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<MemoryType>(""""UNKNOWN"""")
        }
    }
}

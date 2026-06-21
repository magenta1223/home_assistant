package com.homeassistant.core.memory

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryTypeTest {
    private val json = Json

    @Test
    fun `serializes memory type as its code`() {
        assertEquals(""""STATE"""", json.encodeToString(MemoryType.STATE))
        assertEquals(""""EVENT"""", json.encodeToString(MemoryType.EVENT))
        assertEquals(""""CHECKLIST"""", json.encodeToString(MemoryType.CHECKLIST))
    }

    @Test
    fun `deserializes memory type code to concrete implementation`() {
        assertEquals(MemoryType.STATE, json.decodeFromString<MemoryType>(""""STATE""""))
        assertEquals(MemoryType.EVENT, json.decodeFromString<MemoryType>(""""EVENT""""))
        assertEquals(MemoryType.CHECKLIST, json.decodeFromString<MemoryType>(""""CHECKLIST""""))
    }

    @Test
    fun `memory type entries expose group code and code`() {
        assertEquals("SEMANTIC", MemoryType.STATE.groupCode)
        assertEquals("STATE", MemoryType.STATE.code)
        assertEquals("EPISODIC", MemoryType.EVENT.groupCode)
        assertEquals("EVENT", MemoryType.EVENT.code)
        assertEquals("PROCEDURAL", MemoryType.CHECKLIST.groupCode)
        assertEquals("CHECKLIST", MemoryType.CHECKLIST.code)
    }

    @Test
    fun `deserialization rejects unknown memory type`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<MemoryType>(""""UNKNOWN"""")
        }
    }
}

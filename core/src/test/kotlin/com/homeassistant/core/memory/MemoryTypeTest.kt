package com.homeassistant.core.memory

import com.homeassistant.core.utils.JsonSerializer
import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import com.homeassistant.core.utils.JsonSerializer.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryTypeTest {

    @Test
    fun `serializes memory type as its code`() {
        assertEquals(""""STATE"""", MemoryType.STATE.encodeToString())
        assertEquals(""""EVENT"""", MemoryType.EVENT.encodeToString())
        assertEquals(""""CHECKLIST"""", MemoryType.CHECKLIST.encodeToString())
    }

    @Test
    fun `deserializes memory type code to concrete implementation`() {
        assertEquals(MemoryType.STATE, """"STATE"""".decodeFromString())
        assertEquals(MemoryType.EVENT, """"EVENT"""".decodeFromString())
        assertEquals(MemoryType.CHECKLIST, """"CHECKLIST"""".decodeFromString())
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
            """"UNKNOWN"""".decodeFromString<MemoryType>()
        }
    }
}

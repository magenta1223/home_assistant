package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryTest {
    @Test
    fun `private canonical memory is visible only to its creator`() {
        val memory = memory(MemoryVisibility.PRIVATE)

        assertTrue(memory.isVisibleTo(UserId("dad")))
        assertFalse(memory.isVisibleTo(UserId("mom")))
    }

    @Test
    fun `canonical memory requires content subject creator and evidence`() {
        assertFailsWith<IllegalArgumentException> {
            memory(MemoryVisibility.FAMILY).copy(evidenceRefs = emptyList())
        }
    }

    private fun memory(visibility: MemoryVisibility) =
        Memory(
            id = 1,
            topicId = 7,
            createdByUserId = "dad",
            content = "리모컨은 현관 수납장에 있다.",
            subject = "리모컨",
            memoryType = MemoryType.LOCATION,
            certainty = MemoryCertainty.SAID,
            visibility = visibility,
            evidenceRefs = listOf(3),
        )
}

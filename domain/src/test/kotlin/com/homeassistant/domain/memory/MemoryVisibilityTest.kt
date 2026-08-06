package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryVisibilityTest {
    @Test
    fun `family memories are globally visible to authorized users`() {
        assertTrue(MemoryVisibility.FAMILY.isVisibleTo(UserId("dad"), UserId("mom")))
    }

    @Test
    fun `private memories are visible only to their creator`() {
        assertTrue(MemoryVisibility.PRIVATE.isVisibleTo(UserId("dad"), UserId("dad")))
        assertFalse(MemoryVisibility.PRIVATE.isVisibleTo(UserId("dad"), UserId("mom")))
    }
}

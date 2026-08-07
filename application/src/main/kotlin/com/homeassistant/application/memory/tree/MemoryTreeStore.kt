package com.homeassistant.application.memory.tree

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

/** Mutates the single-parent memory tree without introducing relation types. */
interface MemoryTreeStore {
    /** Adds a child memory id to an existing structural memory. */
    fun attachChild(userId: UserId, parentMemoryId: Int, childMemoryId: Int): Memory
}

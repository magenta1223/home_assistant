package com.homeassistant.application.memory.save

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryProposal

/** Persists one flat memory proposal as a root memory. */
fun interface MemoryCreator {
    fun create(proposal: MemoryProposal, createdBy: UserId): Memory
}

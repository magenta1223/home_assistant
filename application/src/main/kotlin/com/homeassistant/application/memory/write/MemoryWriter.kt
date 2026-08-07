package com.homeassistant.application.memory.write

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryProposal

/** Persists one memory proposal, optionally under an existing parent. */
interface MemoryWriter {
    fun write(proposal: MemoryProposal, createdBy: UserId): Memory
}

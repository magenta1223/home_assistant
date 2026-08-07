package com.homeassistant.application.memory.save

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryProposal

/** Persists one memory proposal, optionally under an existing parent. */
interface MemoryCreator {
    fun create(proposal: MemoryProposal, createdBy: UserId): Memory

}

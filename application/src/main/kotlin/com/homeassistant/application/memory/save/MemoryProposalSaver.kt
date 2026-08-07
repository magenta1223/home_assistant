package com.homeassistant.application.memory.save

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryProposal

/** Persists analyzed flat memory proposals and schedules their indexing. */
fun interface MemoryProposalSaver {
    fun save(userId: UserId, proposals: List<MemoryProposal>): List<Memory>
}

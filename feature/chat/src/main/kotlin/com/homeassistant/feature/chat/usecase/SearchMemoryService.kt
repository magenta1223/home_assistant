package com.homeassistant.feature.chat.usecase

import com.homeassistant.application.port.inbound.chat.SearchMemory
import com.homeassistant.domain.identity.UserId

/** Searches [memory] for a user, in the same reference format a chat prompt uses. */
class SearchMemoryService(
    private val memory: ContextAssembler,
) : SearchMemory {
    override fun search(userId: UserId, query: String): String =
        memory.context(userId, query).reference.ifEmpty { NO_MATCH }

    companion object {
        const val NO_MATCH = "No stored memory matches this query."
    }
}

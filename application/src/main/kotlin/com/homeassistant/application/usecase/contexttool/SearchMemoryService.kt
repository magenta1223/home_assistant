package com.homeassistant.application.usecase.contexttool

import com.homeassistant.application.port.inbound.contexttool.SearchMemory
import com.homeassistant.application.usecase.contextprovider.ContextSource
import com.homeassistant.domain.identity.UserId

/** Searches [memory] for a user, in the same reference format a chat prompt uses. */
class SearchMemoryService(
    private val memory: ContextSource,
) : SearchMemory {
    override fun search(userId: UserId, query: String): String =
        memory.context(userId, query).reference.ifEmpty { NO_MATCH }

    companion object {
        const val NO_MATCH = "No stored memory matches this query."
    }
}

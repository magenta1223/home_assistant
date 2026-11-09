package com.homeassistant.application.usecase.contexttool

import com.homeassistant.application.port.inbound.contexttool.SearchMemory
import com.homeassistant.application.usecase.contextprovider.ContextSource

/** Searches [memory] as the user a context token was issued for, in the same reference format a chat prompt uses. */
class SearchMemoryService(
    private val grants: ContextGrants,
    private val memory: ContextSource,
) : SearchMemory {
    override fun search(contextToken: String, query: String): String =
        memory.context(grants.ownerOf(contextToken), query).reference.ifEmpty { NO_MATCH }

    companion object {
        const val NO_MATCH = "No stored memory matches this query."
    }
}

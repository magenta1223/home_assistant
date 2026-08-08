package com.homeassistant.application.port.input.memory.search

/** Retrieves ranked canonical memories visible to the caller. */
fun interface MemorySearch {
    fun search(request: SearchMemoriesRequest): SearchMemoriesResult
}

class MemorySearchUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("memory search is unavailable", cause)

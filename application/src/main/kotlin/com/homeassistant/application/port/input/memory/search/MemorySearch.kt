package com.homeassistant.application.port.input.memory.search

/** Retrieves ranked canonical memories visible to the caller. */
fun interface MemorySearch {
    fun search(request: SearchMemoriesRequest): SearchMemoriesResult
}

class MemorySearchUnavailableException(message: String) : RuntimeException(message)

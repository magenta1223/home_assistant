package com.homeassistant.application.port.inbound.memory

import com.homeassistant.domain.source.SourceDescriptor

/** Memorizes the source records stored since the last update with the configured memory method. */
fun interface UpdateMemory {
    /**
     * Memorizes the stored records of every source waiting for it, then lets the memory method organize what it
     * memorized, reading [batchSize] items at a time. Each source is tried at most once per call; one that fails waits
     * for the next call. Nothing runs when the memory method's services are unavailable, so no attempt is used up.
     */
    suspend fun execute(batchSize: Int): MemoryUpdateResult
}

sealed interface MemoryUpdateResult {
    /** The memory method's services were unavailable; nothing was read or written. */
    data object Skipped : MemoryUpdateResult

    /** Every step ran. */
    data class Completed(val sources: List<SourceMemorizationResult>) : MemoryUpdateResult

    /** [sources] were memorized, then organizing stopped for [reason] and waits for the next call. */
    data class Stopped(val sources: List<SourceMemorizationResult>, val reason: String) : MemoryUpdateResult
}

/** How memorizing the waiting records of [source] ended. */
data class SourceMemorizationResult(val source: SourceDescriptor, val outcome: SourceMemorizationOutcome)

sealed interface SourceMemorizationOutcome {
    /** [recordCount] records of the source were memorized. */
    data class Memorized(val recordCount: Int) : SourceMemorizationOutcome

    /** The records stay waiting and are tried again by a later call. */
    data class Failed(val reason: String) : SourceMemorizationOutcome
}

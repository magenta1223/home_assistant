package com.homeassistant.application.port.output.topic

/** Trusted internal records, never an authorized projection for an end user. */
data class TopicPlacementMemory(val id: Int, val content: String)
data class TopicCandidate(val id: Int, val description: String)

interface TopicPlacementStore {
    /** Oldest canonical memories with no Topic membership, bounded by a positive limit. */
    fun loadUnplaced(limit: Int): List<TopicPlacementMemory>

    /** Bounded newest-topic maintenance window; this is not semantic search. */
    fun loadCandidates(limit: Int): List<TopicCandidate>

    /**
     * Checks coverage/existence and current unplaced state in the same transaction as the write.
     * All unplaced: apply atomically. None unplaced: AlreadyPlaced. Partial overlap: Stale, no write.
     * Batch membership IDs must exactly cover memoryIds. Missing memories fail without changes.
     */
    fun commitIfUnplaced(memoryIds: Set<Int>, batch: TopicWriteBatch): TopicPlacementCommitResult
}

sealed interface TopicPlacementCommitResult {
    data class Placed(val writeResult: TopicWriteResult) : TopicPlacementCommitResult
    data object AlreadyPlaced : TopicPlacementCommitResult
    data object Stale : TopicPlacementCommitResult
}

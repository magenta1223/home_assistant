package com.homeassistant.application.port.output.topic

/** Bounded trusted organizer context; contains no permission grant for external users. */
data class TopicPlacementInput(
    val memories: List<TopicPlacementMemory>,
    val candidates: List<TopicCandidate>,
)

/** Creation keys in the returned proposal are local to this input, not persisted identities. */
fun interface TopicPlacementProposer {
    suspend fun propose(input: TopicPlacementInput): TopicWriteBatch
}

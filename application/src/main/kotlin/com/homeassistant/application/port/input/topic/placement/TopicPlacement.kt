package com.homeassistant.application.port.input.topic.placement

/** Trusted internal ingestion/maintenance operation, not a user-facing Topic read or mutation API. */
fun interface TopicPlacement {
    suspend fun processAvailable(): TopicPlacementRunResult
}

sealed interface TopicPlacementRunResult {
    data object Idle : TopicPlacementRunResult
    data class Placed(val memoryCount: Int) : TopicPlacementRunResult
    data object AlreadyPlaced : TopicPlacementRunResult
    data object Stale : TopicPlacementRunResult
    /** Failed memories remain discoverable by the next run. No content or provider error is exposed. */
    data class Failed(val stage: TopicPlacementFailureStage) : TopicPlacementRunResult
}

enum class TopicPlacementFailureStage { LOAD, PROPOSE, VALIDATE, SAVE }

class TopicPlacementException internal constructor(
    val stage: TopicPlacementFailureStage,
    cause: Throwable,
) : RuntimeException("Topic placement failed during $stage", cause)

package com.homeassistant.application.usecase.topic.placement

import com.homeassistant.application.port.input.topic.placement.*
import com.homeassistant.application.port.output.topic.*
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class TopicPlacementService(
    private val store: TopicPlacementStore,
    private val proposer: TopicPlacementProposer,
    private val memoryLimit: Int = 20,
    private val candidateLimit: Int = 20,
) : TopicPlacement {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(memoryLimit > 0) { "memoryLimit must be positive" }
        require(candidateLimit > 0) { "candidateLimit must be positive" }
    }

    override suspend fun processAvailable(): TopicPlacementRunResult = try {
        val memories = stage(TopicPlacementFailureStage.LOAD) { store.loadUnplaced(memoryLimit) }
        if (memories.isEmpty()) {
            TopicPlacementRunResult.Idle
        } else {
            place(memories)
        }
    } catch (error: TopicPlacementException) {
        log.warn("Topic placement deferred stage={} category={}", error.stage, error.cause?.javaClass?.simpleName)
        TopicPlacementRunResult.Failed(error.stage)
    }

    private suspend fun place(memories: List<TopicPlacementMemory>): TopicPlacementRunResult {
        val candidates = stage(TopicPlacementFailureStage.LOAD) { store.loadCandidates(candidateLimit) }
        val input = TopicPlacementInput(memories, candidates)
        val proposal = stage(TopicPlacementFailureStage.PROPOSE) { proposer.propose(input) }
        val batch = stage(TopicPlacementFailureStage.VALIDATE) { TopicPlacementProposalValidator.prepare(input, proposal) }
        val result = stage(TopicPlacementFailureStage.SAVE) {
            store.commitIfUnplaced(memories.map { it.id }.toSet(), batch)
        }
        return when (result) {
            is TopicPlacementCommitResult.Placed -> TopicPlacementRunResult.Placed(memories.size)
            TopicPlacementCommitResult.AlreadyPlaced -> TopicPlacementRunResult.AlreadyPlaced
            TopicPlacementCommitResult.Stale -> TopicPlacementRunResult.Stale
        }
    }

    private suspend fun <T> stage(stage: TopicPlacementFailureStage, action: suspend () -> T): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw TopicPlacementException(stage, error)
    }
}

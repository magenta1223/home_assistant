package com.homeassistant.application.usecase.memory.placement

import com.homeassistant.application.port.input.memory.placement.MemoryPlaceRequest
import com.homeassistant.application.port.input.memory.placement.MemoryPlacement
import com.homeassistant.application.port.input.memory.placement.MemoryPlacementException
import com.homeassistant.application.port.output.memory.placement.MemoryPlacementExtractor
import com.homeassistant.application.port.output.memory.placement.MemoryPlacementInput
import com.homeassistant.application.port.output.memory.placement.MemoryTreeAttachRequest
import com.homeassistant.application.port.output.memory.placement.MemoryTreeStore
import com.homeassistant.application.port.output.memory.read.MemoryReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orchestrates one visible-tree placement pass for one saved memory batch. */
class MemoryPlacementService(
    private val memoryReader: MemoryReader,
    private val extractor: MemoryPlacementExtractor,
    private val tree: MemoryTreeStore,
    private val visibleTreeDepth: Int = DEFAULT_VISIBLE_TREE_DEPTH,
) : MemoryPlacement {
    private val placementLock = Mutex()

    init {
        require(visibleTreeDepth >= 0) { "visibleTreeDepth must not be negative" }
    }

    override suspend fun place(memoryPlaceRequest: MemoryPlaceRequest) {
        if (memoryPlaceRequest.memories.isEmpty()) return

        try {
            placementLock.withLock {
                placeBatch(memoryPlaceRequest)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MemoryPlacementException) {
            throw error
        } catch (error: Exception) {
            throw MemoryPlacementException("memory placement failed", error)
        }
    }

    private suspend fun placeBatch(request: MemoryPlaceRequest) {
        val inputMemoryIds = request.memories.map { it.id }
        val existingMemories = memoryReader.getMemories(request.userId)
            .filterNot { it.id in inputMemoryIds }

        val visibleTree = MemoryPlacementTreeRenderer.render(existingMemories, visibleTreeDepth)
        val placementInput = MemoryPlacementInput(
            memories = request.memories,
            visibleMemoryTree = visibleTree.text,
        )
        val response = extractor.analyze(placementInput)

        MemoryPlacementResponseValidator.validate(
            input = placementInput,
            response = response,
            selectableMemoryIds = visibleTree.selectableMemoryIds,
        )
        val attachRequest = MemoryTreeAttachRequest(
            userId = request.userId,
            parentByChild = response.decisions
                .mapNotNull { decision ->
                    decision.parentId?.let { parentId -> decision.memoryId to parentId }
                }
                .toMap(),
        )

        if (attachRequest.parentByChild.isEmpty()) return

        tree.attachChildren(attachRequest)
    }

    private companion object {
        const val DEFAULT_VISIBLE_TREE_DEPTH = 2
    }
}

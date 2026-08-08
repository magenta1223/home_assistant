package com.homeassistant.application.memory.tree

import com.homeassistant.application.memory.read.MemoryReader
import com.homeassistant.application.memory.write.SemanticMemoryIndexWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orchestrates one visible-tree placement pass for one saved memory batch. */
class MemoryPlacementService(
    private val memoryReader: MemoryReader,
    private val extractor: MemoryPlacementExtractor,
    private val tree: MemoryTreeStore,
    private val memoryIndexWriter: SemanticMemoryIndexWriter,
    private val visibleTreeDepth: Int = DEFAULT_VISIBLE_TREE_DEPTH,
) : MemoryPlacement {
    private val placementLock = Mutex()

    init {
        require(visibleTreeDepth >= 0) { "visibleTreeDepth must not be negative" }
    }

    override suspend fun place(memoryPlaceRequest: MemoryPlaceRequest) {
        if (memoryPlaceRequest.memories.isEmpty()) return
        validateRequest(memoryPlaceRequest)

        placementLock.withLock {
            placeBatch(memoryPlaceRequest)
        }
    }

    private fun validateRequest(request: MemoryPlaceRequest) {
        val memoryIds = request.memories.map { it.id }
        if (memoryIds.size != memoryIds.toSet().size) {
            throw MemoryPlacementException("Placement request contains duplicate memory ids")
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
        val orderedResponse = MemoryPlacementResponseOrderer.order(response, inputMemoryIds)
        val attachRequest = MemoryTreeAttachRequest(
            userId = request.userId,
            parentByChild = orderedResponse.decisions
                .mapNotNull { decision ->
                    decision.parentId?.let { parentId -> decision.memoryId to parentId }
                }
                .toMap(),
        )

        if (attachRequest.parentByChild.isEmpty()) return

        val attachResponse = tree.attachChildren(attachRequest)
        attachResponse.updatedMemories
            .distinctBy { it.id }
            .forEach { memoryIndexWriter.upsert(it) }
    }

    private companion object {
        const val DEFAULT_VISIBLE_TREE_DEPTH = 2
    }
}

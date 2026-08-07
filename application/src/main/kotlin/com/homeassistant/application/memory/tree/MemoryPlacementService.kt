package com.homeassistant.application.memory.tree

import com.homeassistant.application.memory.write.SemanticMemoryIndexWriter
import com.homeassistant.application.memory.read.SemanticMemoryIndexSearcher
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Places a batch of new root memories without moving existing non-root memories.
 * Embeddings find candidates; the extractor makes one decision per memory.
 */
class MemoryPlacementService(
    private val extractor: MemoryPlacementExtractor,
    private val tree: MemoryTreeStore,
    private val searcher: SemanticMemoryIndexSearcher,
    private val memoryIndexWriter: SemanticMemoryIndexWriter,
) : MemoryPlacement {
    private val placementLock = Mutex()


    override suspend fun place(userId: UserId, memories: List<Memory>) {
        placementLock.withLock {
            memories
                .asSequence()
                .chunked(BATCH_SIZE)
//                .forEach { placeBatch(userId, it) }
        }
    }

//    private suspend fun placeBatch(userId: UserId, batch: List<Memory>) {
//        if (batch.isEmpty()) return
//
//        val rootMemories = memoryRetriever.getMemories(userId)
//            .map { it.id }
//            .toSet()
//
//        val inputs = batch.map { memory ->
//            val rootHits = searcher.search(
//                query = "${memory.subject}\n${memory.content}",
//                limit = CANDIDATE_LIMIT,
//                scope = MemoryIndexSearchScope(
//                    allowedMemoryIds = rootMemories,
//                ),
//            )
//            MemoryPlacementInput(
//                memory = memory,
//                candidates = findStructuralCandidates(
//                    query = "${memory.subject}\n${memory.content}",
//                    rootHits = rootHits,
//                ),
//            )
//        }
//        val result = extractor.analyze(inputs)
//        val inputById = inputs.associateBy { it.memory.id }
//        val decisions = result.decisions.associateBy { it.memoryId }
//        inputById.values.forEach { input ->
//            val decision = decisions[input.memory.id] ?: return@forEach
//            when (decision.decision) {
//                MemoryPlacementDecisionType.EXISTING_PARENT -> {
//                    val containerId = decision.containerId ?: return@forEach
//                    if (input.candidates.none { it.id == containerId }) return@forEach
//                    attach(input.memory, containerId)
//                }
//                MemoryPlacementDecisionType.ROOT -> Unit
//            }
//        }
//    }
//
//    private fun attach(memory: Memory, containerId: Int) {
//        val updatedContainer = tree.attachChild(containerId, memory.id)
//        indexing.index(updatedContainer)
//    }
//
//    private fun findStructuralCandidates(
//        query: String,
//        rootHits: List<MemoryIndex>,
//    ): List<Memory> {
//        val roots = memoryRetriever.getMemories(rootHits.map { it.memoryId })
//            .filter { it.visibility == MemoryVisibility.STRUCTURAL }
//            .associateBy { it.id }
//        return rootHits
//            .mapNotNull { hit -> roots[hit.memoryId]?.let { it to hit.score } }
//            .flatMap { (root, score) ->
//                listOf(root to score) + findStructuralChildren(query, root.id, 0)
//            }
//            .sortedByDescending { it.second }
//            .distinctBy { it.first.id }
//            .take(CANDIDATE_LIMIT)
//            .map { it.first }
//    }
//
//    private fun findStructuralChildren(
//        query: String,
//        containerId: Int,
//        depth: Int,
//    ): List<Pair<Memory, Double>> {
//        if (depth >= MAX_TREE_SEARCH_DEPTH) return emptyList()
//        val parent = memoryRetriever.getMemories(listOf(containerId)).singleOrNull() ?: return emptyList()
//        if (parent.childrenIds.isEmpty()) return emptyList()
//        val childHits = searcher.search(
//            query = query,
//            limit = CANDIDATE_LIMIT,
//            scope = MemoryIndexSearchScope(allowedMemoryIds = parent.childrenIds.toSet()),
//        )
//        val children = memoryRetriever.getMemories(childHits.map { it.memoryId }).associateBy { it.id }
//        return childHits.flatMap { hit ->
//            val child = children[hit.memoryId] ?: return@flatMap emptyList()
//            if (child.visibility != MemoryVisibility.STRUCTURAL) emptyList()
//            else listOf(child to hit.score) + findStructuralChildren(query, child.id, depth + 1)
//        }
//    }

    private companion object {
        const val BATCH_SIZE = 30
        const val CANDIDATE_LIMIT = 8
        const val MAX_TREE_SEARCH_DEPTH = 12
    }
}

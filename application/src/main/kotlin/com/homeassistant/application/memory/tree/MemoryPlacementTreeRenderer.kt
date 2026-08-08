package com.homeassistant.application.memory.tree

import com.homeassistant.domain.memory.Memory

data class MemoryPlacementTree(
    val text: String,
    val selectableMemoryIds: Set<Int>,
)

/** Renders the visible part of the existing memory tree for placement. */
object MemoryPlacementTreeRenderer {
    fun render(memories: List<Memory>, maxDepth: Int): MemoryPlacementTree {
        require(maxDepth >= 0) { "maxDepth must not be negative" }
        if (memories.isEmpty()) {
            return MemoryPlacementTree(
                text = "(기존 memory 없음)",
                selectableMemoryIds = emptySet(),
            )
        }

        val memoriesById = memories.associateBy { it.id }
        val referencedChildIds = memories
            .flatMap { it.childrenIds }
            .filter { it in memoriesById }
            .toSet()
        val roots = memories
            .filter { it.id !in referencedChildIds }
            .sortedBy { it.id }
        val renderedIds = linkedSetOf<Int>()
        val lines = mutableListOf<String>()

        fun renderMemory(memoryId: Int, depth: Int) {
            if (memoryId in renderedIds) return
            val memory = memoriesById[memoryId] ?: return
            renderedIds += memoryId
            val indentation = "  ".repeat(depth)
            val subject = memory.subject.replace(WHITESPACE, " ").trim()
            val content = memory.content.replace(WHITESPACE, " ").trim()
            lines += "$indentation- [${memory.id}] $subject | $content"
            if (depth >= maxDepth) return
            memory.childrenIds.forEach { childId -> renderMemory(childId, depth + 1) }
        }

        roots.forEach { renderMemory(it.id, 0) }
        // If no root can be inferred from malformed legacy data, expose it as a forest.
        if (roots.isEmpty()) memories.sortedBy { it.id }.forEach { renderMemory(it.id, 0) }

        return MemoryPlacementTree(
            text = lines.joinToString("\n"),
            selectableMemoryIds = renderedIds,
        )
    }

    private val WHITESPACE = Regex("\\s+")
}

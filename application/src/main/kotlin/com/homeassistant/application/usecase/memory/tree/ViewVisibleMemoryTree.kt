package com.homeassistant.application.usecase.memory.tree

import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTree
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeNode
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeRequest
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeResult
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeUnavailableException
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

class ViewVisibleMemoryTree(
    private val memories: MemoryReader,
    private val accessPolicy: UserAccessPolicy,
) : VisibleMemoryTree {
    override fun get(request: VisibleMemoryTreeRequest): VisibleMemoryTreeResult {
        val userId = UserId(request.userId)
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()

        val visibleMemories = try {
            memories.getMemories(userId)
        } catch (error: Exception) {
            throw VisibleMemoryTreeUnavailableException(error)
        }
        return VisibleMemoryTreeProjector.project(visibleMemories)
    }
}

/** Builds a deterministic, cycle-safe forest without changing stored memory relationships. */
internal object VisibleMemoryTreeProjector {
    fun project(memories: List<Memory>): VisibleMemoryTreeResult {
        val memoriesById = memories.associateBy(Memory::id)
        if (memoriesById.isEmpty()) return VisibleMemoryTreeResult(emptyList(), 0)

        val parentByChild = linkedMapOf<Int, Int>()
        memoriesById.values.sortedBy(Memory::id).forEach { parent ->
            parent.childrenIds
                .asSequence()
                .filter { childId -> childId != parent.id && childId in memoriesById }
                .distinct()
                .sorted()
                .forEach { childId -> parentByChild.putIfAbsent(childId, parent.id) }
        }

        val visited = mutableSetOf<Int>()

        fun buildNode(memoryId: Int, path: Set<Int>): VisibleMemoryTreeNode? {
            if (memoryId in visited || memoryId in path) return null
            val memory = memoriesById[memoryId] ?: return null
            visited += memoryId
            val nextPath = path + memoryId
            val children = memory.childrenIds
                .asSequence()
                .filter { childId -> parentByChild[childId] == memoryId }
                .distinct()
                .sorted()
                .mapNotNull { childId -> buildNode(childId, nextPath) }
                .toList()
            return memory.toTreeNode(children)
        }

        val rootIds = memoriesById.keys
            .filter { memoryId -> memoryId !in parentByChild }
            .sorted()
        val roots = rootIds.mapNotNull { memoryId -> buildNode(memoryId, emptySet()) }.toMutableList()

        memoriesById.keys.sorted().forEach { memoryId ->
            buildNode(memoryId, emptySet())?.let(roots::add)
        }

        return VisibleMemoryTreeResult(
            roots = roots.sortedBy(VisibleMemoryTreeNode::memoryId),
            totalCount = memoriesById.size,
        )
    }

    private fun Memory.toTreeNode(children: List<VisibleMemoryTreeNode>) = VisibleMemoryTreeNode(
        memoryId = id,
        subject = subject,
        content = content,
        memoryType = memoryType,
        certainty = certainty,
        createdAt = createdAt,
        children = children,
    )
}

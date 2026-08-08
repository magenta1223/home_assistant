package com.homeassistant.application.memory.tree

import com.homeassistant.application.memory.read.MemoryReader
import com.homeassistant.application.memory.write.SemanticMemoryIndexWriter
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class MemoryPlacementServiceTest {
    private val userId = UserId("member-1")

    @Test
    fun `renders visible tree through configured depth and calls extractor once`() = runBlocking {
        val root = memory(1, childrenIds = listOf(2))
        val child = memory(2, childrenIds = listOf(3))
        val grandchild = memory(3, childrenIds = listOf(4))
        val omitted = memory(4)
        val incoming = memory(100)
        val extractor = RecordingExtractor {
            MemoryPlacementResponse(
                decisions = listOf(MemoryPlacementDecision(incoming.id, child.id)),
            )
        }
        val tree = RecordingTree()
        val service = service(
            memories = listOf(root, child, grandchild, omitted, incoming),
            extractor = extractor,
            tree = tree,
            visibleTreeDepth = 2,
        )

        service.place(MemoryPlaceRequest(userId, listOf(incoming)))

        assertEquals(1, extractor.calls)
        assertEquals(listOf(2 to 100), tree.parentChildIds)
        assertEquals(1, tree.calls)
        assertEquals(
            "- [1] subject-1 | memory-1\n" +
                "  - [2] subject-2 | memory-2\n" +
                "    - [3] subject-3 | memory-3",
            extractor.input.visibleMemoryTree,
        )
        assertFalse(extractor.input.visibleMemoryTree.contains("[4]"))
    }

    @Test
    fun `null parent keeps every memory in the root without tree mutation`() = runBlocking {
        val incoming = memory(100)
        val extractor = RecordingExtractor {
            MemoryPlacementResponse(
                decisions = listOf(MemoryPlacementDecision(incoming.id, null)),
            )
        }
        val tree = RecordingTree()
        service(
            memories = listOf(incoming),
            extractor = extractor,
            tree = tree,
        ).place(MemoryPlaceRequest(userId, listOf(incoming)))

        assertEquals(1, extractor.calls)
        assertEquals(0, tree.calls)
    }

    @Test
    fun `orders assignments when a new memory is the parent of another new memory`() = runBlocking {
        val existingRoot = memory(1)
        val first = memory(100)
        val second = memory(101)
        val extractor = RecordingExtractor {
            // Deliberately return the child first; persistence order must still be parent-first.
            MemoryPlacementResponse(
                decisions = listOf(
                    MemoryPlacementDecision(first.id, second.id),
                    MemoryPlacementDecision(second.id, existingRoot.id),
                ),
            )
        }
        val tree = RecordingTree()
        service(
            memories = listOf(existingRoot, first, second),
            extractor = extractor,
            tree = tree,
        ).place(MemoryPlaceRequest(userId, listOf(first, second)))

        assertEquals(
            listOf(1 to 101, 101 to 100),
            tree.parentChildIds,
        )
    }

    @Test
    fun `rejects a parent that is not in the rendered visible tree`() = runBlocking {
        val incoming = memory(100)
        val extractor = RecordingExtractor {
            MemoryPlacementResponse(
                decisions = listOf(MemoryPlacementDecision(incoming.id, 999)),
            )
        }
        val tree = RecordingTree()

        assertFailsWith<MemoryPlacementException> {
            service(
                memories = listOf(incoming),
                extractor = extractor,
                tree = tree,
            ).place(MemoryPlaceRequest(userId, listOf(incoming)))
        }
        assertEquals(0, tree.calls)
    }

    @Test
    fun `rejects a cycle among batch memories before persistence`() = runBlocking {
        val first = memory(100)
        val second = memory(101)
        val extractor = RecordingExtractor {
            MemoryPlacementResponse(
                decisions = listOf(
                    MemoryPlacementDecision(first.id, second.id),
                    MemoryPlacementDecision(second.id, first.id),
                ),
            )
        }
        val tree = RecordingTree()

        assertFailsWith<MemoryPlacementException> {
            service(
                memories = listOf(first, second),
                extractor = extractor,
                tree = tree,
            ).place(MemoryPlaceRequest(userId, listOf(first, second)))
        }
        assertEquals(0, tree.calls)
    }

    @Test
    fun `rejects incomplete or duplicate extractor responses`() = runBlocking {
        val first = memory(100)
        val second = memory(101)
        val incompleteExtractor = RecordingExtractor {
            MemoryPlacementResponse(
                decisions = listOf(MemoryPlacementDecision(first.id, null)),
            )
        }
        assertFailsWith<MemoryPlacementException> {
            service(
                memories = listOf(first, second),
                extractor = incompleteExtractor,
                tree = RecordingTree(),
            ).place(MemoryPlaceRequest(userId, listOf(first, second)))
        }

        val duplicateExtractor = RecordingExtractor {
            MemoryPlacementResponse(
                decisions = listOf(
                    MemoryPlacementDecision(first.id, null),
                    MemoryPlacementDecision(first.id, null),
                ),
            )
        }
        assertFailsWith<MemoryPlacementException> {
            service(
                memories = listOf(first, second),
                extractor = duplicateExtractor,
                tree = RecordingTree(),
            ).place(MemoryPlaceRequest(userId, listOf(first, second)))
        }
    }

    private fun service(
        memories: List<Memory>,
        extractor: RecordingExtractor,
        tree: RecordingTree,
        visibleTreeDepth: Int = 2,
    ) = MemoryPlacementService(
        memoryReader = FixedMemoryReader(memories),
        extractor = extractor,
        tree = tree,
        memoryIndexWriter = SemanticMemoryIndexWriter { true },
        visibleTreeDepth = visibleTreeDepth,
    )

    private fun memory(
        id: Int,
        childrenIds: List<Int> = emptyList(),
        visibility: MemoryVisibility = MemoryVisibility.PUBLIC,
    ) = Memory(
        id = id,
        childrenIds = childrenIds,
        createdByUserId = userId.value,
        content = "memory-$id",
        subject = "subject-$id",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        visibility = visibility,
        evidenceRefs = listOf(id),
        createdAt = id * 1_000L,
    )

    private class FixedMemoryReader(
        private val memories: List<Memory>,
    ) : MemoryReader {
        override fun getMemories(userId: UserId): List<Memory> = memories
    }

    private class RecordingTree : MemoryTreeStore {
        var calls = 0
        val parentChildIds = mutableListOf<Pair<Int, Int>>()

        override fun attachChildren(request: MemoryTreeAttachRequest): MemoryTreeAttachResponse {
            calls++
            request.parentByChild.forEach { (childId, parentId) ->
                parentChildIds += parentId to childId
            }
            return MemoryTreeAttachResponse(
                updatedMemories = request.parentByChild.values.distinct().map(::memory),
            )
        }

        private fun memory(id: Int) = Memory(
            id = id,
            createdByUserId = "member-1",
            content = "updated-$id",
            subject = "updated-$id",
            memoryType = MemoryType.REFERENCE,
            certainty = MemoryCertainty.OBSERVED,
            visibility = MemoryVisibility.PUBLIC,
            evidenceRefs = listOf(id),
            createdAt = id * 1_000L,
        )
    }

    private class RecordingExtractor(
        private val response: () -> MemoryPlacementResponse,
    ) : MemoryPlacementExtractor {
        var calls = 0
        lateinit var input: MemoryPlacementInput

        override suspend fun analyze(input: MemoryPlacementInput): MemoryPlacementResponse {
            calls++
            this.input = input
            return response()
        }
    }
}

package com.homeassistant.application.usecase.memory.tree

import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeRequest
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeUnavailableException
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ViewVisibleMemoryTreeTest {
    private val userId = UserId("member-1")

    @Test
    fun `returns a deterministic tree containing only memories supplied by the visible reader`() {
        val reader = RecordingReader(
            listOf(
                memory(4),
                memory(2),
                memory(1, childrenIds = listOf(4, 2, 999)),
                memory(3),
            ),
        )

        val result = service(reader).get(VisibleMemoryTreeRequest(userId.value))

        assertEquals(userId, reader.requestedUserId)
        assertEquals(4, result.totalCount)
        assertEquals(listOf(1, 3), result.roots.map { it.memoryId })
        assertEquals(listOf(2, 4), result.roots.first().children.map { it.memoryId })
        assertEquals("subject-2", result.roots.first().children.first().subject)
    }

    @Test
    fun `promotes visible memories whose parent is absent to roots`() {
        val result = service(
            RecordingReader(
                listOf(
                    memory(2, childrenIds = listOf(3)),
                    memory(3),
                ),
            ),
        ).get(VisibleMemoryTreeRequest(userId.value))

        assertEquals(listOf(2), result.roots.map { it.memoryId })
        assertEquals(listOf(3), result.roots.single().children.map { it.memoryId })
    }

    @Test
    fun `shows every memory once when legacy relationships contain a cycle or multiple parents`() {
        val result = service(
            RecordingReader(
                listOf(
                    memory(1, childrenIds = listOf(2, 3)),
                    memory(2, childrenIds = listOf(1, 3)),
                    memory(3),
                    memory(4),
                ),
            ),
        ).get(VisibleMemoryTreeRequest(userId.value))

        assertEquals(listOf(1, 4), result.roots.map { it.memoryId })
        assertEquals(listOf(1, 2, 3, 4), flatten(result.roots).sorted())
        assertEquals(4, result.totalCount)
    }

    @Test
    fun `rejects an unauthorized user before reading memories`() {
        val reader = RecordingReader(emptyList())
        val service = ViewVisibleMemoryTree(reader, UserAccessPolicy { false })

        assertFailsWith<UserAccessDeniedException> {
            service.get(VisibleMemoryTreeRequest(userId.value))
        }
        assertEquals(null, reader.requestedUserId)
    }

    @Test
    fun `maps reader failures to the input port failure contract`() {
        val failure = IllegalStateException("database unavailable")
        val service = service(object : MemoryReader {
            override fun getMemories(userId: UserId): List<Memory> = throw failure
        })

        val error = assertFailsWith<VisibleMemoryTreeUnavailableException> {
            service.get(VisibleMemoryTreeRequest(userId.value))
        }

        assertEquals(failure, error.cause)
    }

    private fun service(reader: MemoryReader) =
        ViewVisibleMemoryTree(reader, UserAccessPolicy { it == userId })

    private fun flatten(nodes: List<com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeNode>): List<Int> =
        nodes.flatMap { node -> listOf(node.memoryId) + flatten(node.children) }

    private fun memory(id: Int, childrenIds: List<Int> = emptyList()) = Memory(
        id = id,
        childrenIds = childrenIds,
        createdByUserId = userId.value,
        content = "content-$id",
        subject = "subject-$id",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        visibility = MemoryVisibility.PUBLIC,
        evidenceRefs = listOf(id),
        createdAt = id.toLong(),
    )

    private class RecordingReader(
        private val memories: List<Memory>,
    ) : MemoryReader {
        var requestedUserId: UserId? = null

        override fun getMemories(userId: UserId): List<Memory> {
            requestedUserId = userId
            return memories
        }
    }
}

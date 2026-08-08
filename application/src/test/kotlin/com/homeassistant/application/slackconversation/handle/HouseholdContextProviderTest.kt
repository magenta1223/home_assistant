package com.homeassistant.application.slackconversation.handle

import com.homeassistant.application.memory.memorygroundedchat.MemoryAnswerContextProvider
import com.homeassistant.application.memory.read.MemoryIndex
import com.homeassistant.application.memory.read.MemoryIndexSearchScope
import com.homeassistant.application.memory.read.MemoryReader
import com.homeassistant.application.memory.read.MemorySearcher
import com.homeassistant.application.memory.read.SemanticMemoryIndexSearcher
import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class HouseholdContextProviderTest {
    @Test
    fun `Slack reference includes a relevant expanded child with provenance`() {
        val parent = memory(1, childrenIds = listOf(2))
        val child = memory(2)
        val memories = listOf(parent, child)
        val reader = FixedMemoryReader(memories)
        val semanticSearcher = object : SemanticMemoryIndexSearcher {
            override fun search(query: String, limit: Int): List<MemoryIndex> =
                listOf(MemoryIndex(parent.id, 0.9)).take(limit)

            override fun search(
                query: String,
                limit: Int,
                scope: MemoryIndexSearchScope,
            ): List<MemoryIndex> {
                val allowedIds = scope.allowedMemoryIds.orEmpty()
                val results = if (allowedIds == memories.mapTo(mutableSetOf()) { it.id }) {
                    listOf(MemoryIndex(parent.id, 0.9))
                } else {
                    listOf(MemoryIndex(child.id, 0.85))
                }
                return results.filter { it.memoryId in allowedIds }.take(limit)
            }
        }
        val memorySearcher = MemorySearcher(
            memories = reader,
            searcher = semanticSearcher,
            accessPolicy = HouseholdAccessPolicy { it == USER_ID },
        )
        val provider = HouseholdContextProvider(
            MemoryAnswerContextProvider(memorySearcher, reader, semanticSearcher),
        )

        val context = provider.context(
            SlackPrincipal("team-1", "slack-1", USER_ID),
            "question",
        )

        assertTrue(context.hasMatches)
        assertContains(context.reference, "memory-${parent.id}")
        assertContains(context.reference, "memory-${child.id}")
        assertContains(context.reference, "source=CHILD")
        assertContains(context.reference, "score=0.85")
        assertContains(context.reference, "parentMemoryId=${parent.id}")
        assertContains(context.reference, "depth=1")
    }

    private fun memory(
        id: Int,
        childrenIds: List<Int> = emptyList(),
    ) = Memory(
        id = id,
        childrenIds = childrenIds,
        createdByUserId = USER_ID.value,
        content = "memory-$id",
        subject = "subject-$id",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        visibility = MemoryVisibility.PUBLIC,
        evidenceRefs = listOf(id),
        createdAt = id * 1_000L,
    )

    private class FixedMemoryReader(
        private val memories: List<Memory>,
    ) : MemoryReader {
        override fun getMemories(userId: UserId): List<Memory> = memories
    }

    private companion object {
        val USER_ID = UserId("member-1")
    }
}

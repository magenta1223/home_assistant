package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.application.port.output.memory.search.MemoryIndex
import com.homeassistant.application.port.output.memory.search.MemoryIndexSearchScope
import com.homeassistant.application.port.output.memory.search.SemanticMemoryIndexSearcher
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.usecase.memory.answer.MemoryAnswerContextProvider
import com.homeassistant.application.usecase.memory.search.MemorySearcher
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.usecase.memory.conversation.MemoryConversationContextProvider
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryRepositoryUseCaseIntegrationTest {
    private val member = UserId("member-1")

    @Test
    fun `search and conversation context read visible memories without caller transaction`() {
        withRepositories("memory-search-integration") { stores ->
            val publicMemory = stores.saveMemory(member, "public", MemoryAccess.PUBLIC)
            val ownRestrictedMemory = stores.saveMemory(
                member,
                "own-restricted",
                MemoryAccess.restricted(listOf(member)),
            )
            val otherRestrictedMemory = stores.saveMemory(
                UserId("member-2"),
                "other-restricted",
                MemoryAccess.restricted(listOf(UserId("member-2"))),
            )
            val index = FilteringIndexSearcher(
                listOf(otherRestrictedMemory.id, publicMemory.id, ownRestrictedMemory.id),
            )
            val searcher = MemorySearcher(
                memories = stores.canonicalMemories,
                searcher = index,
                accessPolicy = UserAccessPolicy { it == member },
            )

            val searchResult = searcher.search(SearchMemoriesRequest(member.value, "question"))

            assertEquals(
                listOf(publicMemory.id, ownRestrictedMemory.id),
                searchResult.matches.map { it.memoryId },
            )
            assertFalse(otherRestrictedMemory.id in index.lastScope.allowedMemoryIds.orEmpty())

            val answerContext = MemoryAnswerContextProvider(searcher)
            val conversationContext = MemoryConversationContextProvider(answerContext).context(
                member,
                "question",
            )

            assertTrue(conversationContext.hasMatches)
            assertTrue(conversationContext.reference.contains(publicMemory.content))
        }
    }

    @Test
    fun `explicit topic storage preserves memory content and audience`() {
        withRepositories("topic-placement-integration") { stores ->
            val memory = stores.saveMemory(member, "incoming", MemoryAccess.restricted(listOf(UserId("other"))))
            val before = stores.canonicalMemories.getMemories(UserId("other"))
            val result = stores.topicMaintenance.apply(
                TopicWriteBatch(
                    creations = listOf(TopicCreation("new", "Topic")),
                    memberships = listOf(TopicMembershipAddition(TopicReference.Created("new"), "Facts", listOf(memory.id))),
                )
            )
            assertEquals("Topic", stores.topicMaintenance.readForMaintenance(result.createdTopicIds.values).single().description)
            assertEquals(before, stores.canonicalMemories.getMemories(UserId("other")))
        }
    }
    private inline fun <T> withRepositories(prefix: String, block: (RepositoryStores) -> T): T {
        val databasePath = Files.createTempFile(prefix, ".db")
        return try {
            block(RepositoryFactory.create(databasePath.toString()))
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun RepositoryStores.saveMemory(
        userId: UserId,
        content: String,
        access: MemoryAccess,
    ): Memory {
        val evidence = sourceRecords.saveAll(
            SourceDescriptor(type = "test", name = content),
            listOf(SourceRecordDraft(content, content)),
            access,
        ).recordsToAnalyze.single()
        val proposal = MemoryProposal(
                content = content,
                evidenceIds = listOf(evidence.id),
            )
        return canonicalMemoryBatchWriter.commit(
            userId,
            listOf(IdempotentMemoryProposal("${userId.value}:$content", proposal)),
            listOf(evidence.id),
        ).single()
    }

    private class FilteringIndexSearcher(
        private val rankedMemoryIds: List<Int>,
    ) : SemanticMemoryIndexSearcher {
        lateinit var lastScope: MemoryIndexSearchScope

        override fun search(query: String, limit: Int): List<MemoryIndex> =
            rankedMemoryIds.take(limit).mapIndexed { index, memoryId ->
                MemoryIndex(memoryId, 1.0 - index * 0.1)
            }

        override fun search(
            query: String,
            limit: Int,
            scope: MemoryIndexSearchScope,
        ): List<MemoryIndex> {
            lastScope = scope
            val allowedIds = scope.allowedMemoryIds
            return rankedMemoryIds
                .filter { allowedIds == null || it in allowedIds }
                .take(limit)
                .mapIndexed { index, memoryId -> MemoryIndex(memoryId, 1.0 - index * 0.1) }
        }
    }
}

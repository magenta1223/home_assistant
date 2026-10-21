package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.port.output.topic.MemoryEmbedding
import com.homeassistant.application.port.output.topic.MemoryEmbeddingKey
import com.homeassistant.application.port.output.topic.TopicJoin
import com.homeassistant.application.usecase.topic.TopicSearcher
import com.homeassistant.application.usecase.memory.conversation.MemoryConversationContextProvider
import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.topic.TopicCentroid
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryRepositoryUseCaseIntegrationTest {
    private val member = UserId("member-1")

    @Test
    fun `conversation context reads only the topic slices the user can see`() {
        withRepositories("topic-search-integration") { stores ->
            val east = TopicVector.normalized(listOf(1.0, 0.0))
            val publicMemory = stores.saveMemory(member, "public", MemoryAccess.PUBLIC)
            val otherRestrictedMemory = stores.saveMemory(
                UserId("member-2"),
                "other-restricted",
                MemoryAccess.restricted(listOf(UserId("member-2"))),
            )
            val hiddenMemory = stores.saveMemory(UserId("member-2"), "hidden", MemoryAccess.restricted(listOf(UserId("member-2"))))
            val first = TopicCentroid.of(east)
            val mixed = stores.topicMaintenance.create(publicMemory.id, first)
            assertTrue(stores.topicMaintenance.join(otherRestrictedMemory.id, listOf(TopicJoin(mixed, first, first + east))))
            stores.topicMaintenance.create(hiddenMemory.id, TopicCentroid.of(east))
            val space = EmbeddingSpace("test", "passage:v1")
            stores.memoryEmbeddings.save(MemoryEmbedding(MemoryEmbeddingKey.of(publicMemory.id, publicMemory.content, space), east))
            val search = TopicSearcher(
                stores.topicMaintenance, stores.groups, { east }, stores.canonicalMemories, stores.memoryEmbeddings, space,
                UserAccessPolicy { it == member },
            )

            val conversationContext = MemoryConversationContextProvider(search).context(member, "question")

            assertTrue(conversationContext.hasMatches)
            assertTrue(conversationContext.reference.contains(publicMemory.content))
            assertFalse(conversationContext.reference.contains(otherRestrictedMemory.content))
            assertFalse(conversationContext.reference.contains(hiddenMemory.content))
            assertEquals(1, conversationContext.reference.lines().count { it.startsWith("## Topic") })
        }
    }

    @Test
    fun `topic storage preserves memory content and audience`() {
        withRepositories("topic-placement-integration") { stores ->
            val access = MemoryAccess.restricted(listOf(UserId("other")))
            val memory = stores.saveMemory(member, "incoming", access)
            val before = stores.canonicalMemories.getMemories(UserId("other"))
            val topicId = stores.topicMaintenance.create(memory.id, TopicCentroid.of(TopicVector.normalized(listOf(1.0, 0.0))))
            assertEquals(mapOf(access to listOf(memory.id)), stores.topicMaintenance.read(listOf(topicId)).single().slices)
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
}

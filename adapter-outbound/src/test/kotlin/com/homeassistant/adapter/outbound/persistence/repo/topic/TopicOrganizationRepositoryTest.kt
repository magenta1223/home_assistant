package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupRepository
import com.homeassistant.adapter.outbound.persistence.repo.group.TopicEmbeddingRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.topic.*
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.usecase.group.*
import com.homeassistant.application.usecase.topic.*
import com.homeassistant.domain.group.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import kotlin.test.*

class TopicOrganizationRepositoryTest {
    private val space = EmbeddingSpace("test", "passage:v1")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))
    private val user = UserId("owner")

    @Test fun `memories become described topics and groups, and a restart has nothing left to organize`() = withDatabase { db, path ->
        val ids = seedMemories(db, "보일러 A/S 접수", "보일러 필터 교체", "화분 물주기")
        val vectors = mapOf("보일러 A/S 접수" to east, "보일러 필터 교체" to east, "화분 물주기" to north)
        val memoriesBefore = MemoryRepository(db).getMemories(user)

        val result = assertIs<MemoryOrganizationResult.Completed>(service(db, vectors).execute(10))

        assertTrue(result.memories.all { it.outcome is MemoryOrganizationOutcome.Organized })
        val snapshot = TopicOrganizationRepository(db).snapshot()
        assertEquals(setOf(setOf(ids[0], ids[1]), setOf(ids[2])), snapshot.topics.map { it.memoryIds }.toSet())
        val topics = TopicRepository(db).readForMaintenance(snapshot.topics.map { it.topicId })
        assertEquals(setOf("요약: 보일러 A/S 접수 / 보일러 필터 교체", "요약: 화분 물주기"), topics.map { it.description }.toSet())
        assertTrue(topics.all { it.memories.keys == setOf(ORGANIZED_TOPIC_CATEGORY) })
        assertTrue(result.descriptions.all { it is TopicDescriptionOutcome.Described })
        assertIs<GroupOrganizationResult.Completed>(result.groups)
        assertEquals(topics.map { it.id }.toSet(), GroupRepository(db).snapshot().groups.flatMap { it.group.topicIds }.toSet())
        assertEquals(memoriesBefore, MemoryRepository(db).getMemories(user))

        val restarted = DatabaseFactory.init(path)
        val again = assertIs<MemoryOrganizationResult.Completed>(service(restarted, vectors).execute(10))
        assertTrue(again.memories.isEmpty() && again.descriptions.isEmpty() && again.groupRefreshes.isEmpty())
    }

    @Test fun `split keeps the original topic id and describes both parts`() = withDatabase { db, _ ->
        val ids = seedMemories(db, "a", "b", "c")
        val result = assertIs<MemoryOrganizationResult.Completed>(service(db, emptyMap(), maxMembers = 2).execute(10))
        val created = (result.memories.first().outcome as MemoryOrganizationOutcome.Organized).topicIds.single()
        val splitOutcome = assertIs<MemoryOrganizationOutcome.Organized>(result.memories.last().outcome)
        assertIs<GroupSplit.Divided>(splitOutcome.split)
        assertEquals(created, splitOutcome.topicIds.first())
        val snapshot = TopicOrganizationRepository(db).snapshot()
        assertEquals(ids.toSet(), snapshot.topics.flatMap { it.memoryIds }.toSet())
        assertTrue(snapshot.topics.all { it.memoryIds.size <= 2 })
        assertEquals(2, result.descriptions.size)
    }

    @Test fun `new membership re-describes a grouped topic and refreshes its group vectors in place`() = withDatabase { db, _ ->
        seedMemories(db, "보일러 A/S 접수")
        val vectors = mapOf("보일러 A/S 접수" to east, "보일러 필터 교체" to east)
        service(db, vectors).execute(10)
        val groupBefore = GroupRepository(db).snapshot().groups.single()
        seedMemories(db, "보일러 필터 교체", key = "second")

        val result = assertIs<MemoryOrganizationResult.Completed>(service(db, vectors).execute(10))

        assertEquals(listOf(TopicDescriptionOutcome.Described(groupBefore.group.topicIds.single())), result.descriptions)
        assertEquals(1, result.groupRefreshes.size)
        val groupAfter = GroupRepository(db).snapshot()
        assertTrue(groupAfter.staleTopics.isEmpty())
        assertEquals(groupBefore.group, groupAfter.groups.single().group)
        assertEquals(groupBefore.revision + 1, groupAfter.groups.single().revision)
    }

    @Test fun `stale revision and already organized memory conflict, and stale summaries are superseded`() = withDatabase { db, _ ->
        val ids = seedMemories(db, "a", "b")
        val repository = TopicOrganizationRepository(db)
        val embeddings = MemoryEmbeddingRepository(db).let { store ->
            repository.pending(10).associate { it.id to store.save(MemoryEmbedding(it.embeddingKey(space), east)) }
        }
        val create = TopicOrganizationChange(emptyMap(), setOf(ids[0]),
            listOf(ResultingTopic(TopicTarget.Created, setOf(ids[0]), east)), embeddings.filterKeys { it == ids[0] })
        val topicId = assertIs<TopicOrganizationWriteResult.Applied>(repository.apply(create)).topicIds.single()
        assertEquals(TopicOrganizationWriteResult.Conflict, repository.apply(create))
        val attach = TopicOrganizationChange(mapOf(topicId to 9), setOf(ids[1]),
            listOf(ResultingTopic(TopicTarget.Existing(topicId), ids.toSet(), east)), embeddings)
        assertEquals(TopicOrganizationWriteResult.Conflict, repository.apply(attach))
        assertIs<TopicOrganizationWriteResult.Applied>(repository.apply(attach.copy(expectedTopics = mapOf(topicId to 1))))
        assertEquals(TopicDescriptionWriteResult.Conflict, repository.describe(topicId, 1, "old summary"))
        assertEquals(TopicDescriptionWriteResult.Applied, repository.describe(topicId, 2, "new summary"))
        assertTrue(repository.pendingDescriptions(10).isEmpty())
    }

    @Test fun `mixing memory embedding models requires a rebuild and writes nothing`() = withDatabase { db, _ ->
        seedMemories(db, "a", "b")
        service(db, emptyMap()).execute(1)
        val other = EmbeddingSpace("new", "passage:v1")
        val result = buildService(db, emptyMap(), 12, other).execute(10)
        assertIs<MemoryOrganizationResult.RebuildRequired>(result)
        assertEquals(1, TopicOrganizationRepository(db).snapshot().embeddings.size)
    }

    private fun seedMemories(db: Database, vararg contents: String, key: String = "first"): List<Int> {
        val source = SourceDescriptor("test", key)
        val record = SourceRecordRepositoryImpl(db).saveAll(source, listOf(SourceRecordDraft(key, contents.joinToString())),
            MemoryAccess.restricted(listOf(user))).recordsToAnalyze.single()
        return MemoryRepository(db).commit(user, contents.mapIndexed { index, content ->
            IdempotentMemoryProposal("$key-$index", MemoryProposal(content, listOf(record.id)))
        }, listOf(record.id)).map { it.id }
    }

    private fun service(db: Database, vectors: Map<String, TopicVector>, maxMembers: Int = 12) =
        buildService(db, vectors, maxMembers, space)

    private fun buildService(db: Database, vectors: Map<String, TopicVector>, maxMembers: Int, memorySpace: EmbeddingSpace): OrganizePendingMemoriesService {
        val organization = TopicOrganizationRepository(db)
        val groups = GroupRepository(db)
        val memoryGenerator = object : MemoryEmbeddingGenerator {
            override val space = memorySpace
            override fun generate(content: String) = vectors[content] ?: east
        }
        val topicGenerator = object : TopicEmbeddingGenerator {
            override val space = this@TopicOrganizationRepositoryTest.space
            override fun generate(description: String) = east
        }
        val topicSettings = GroupPolicySettings(maxTopics = maxMembers)
        val groupSettings = GroupPolicySettings()
        val topicEmbeddings = PrepareTopicEmbedding(topicGenerator, TopicEmbeddingRepository(db))
        return OrganizePendingMemoriesService(
            organization,
            OrganizeMemory(organization, organization, PrepareMemoryEmbedding(memoryGenerator, MemoryEmbeddingRepository(db)),
                GroupOrganizationPlanner(GroupPlacementPolicy(topicSettings), GroupSplitPolicy(topicSettings))),
            DescribeOrganizedTopics(organization, { "요약: " + it.joinToString(" / ") }, organization),
            RefreshGroupedTopicEmbeddings(groups, groups, topicEmbeddings),
            OrganizePendingTopicsService(groups, OrganizeTopic(groups, groups, topicEmbeddings,
                GroupOrganizationPlanner(GroupPlacementPolicy(groupSettings), GroupSplitPolicy(groupSettings)))),
        )
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("topic-organization", ".db")
        try { block(DatabaseFactory.init(path.toString()), path.toString()) }
        finally { Files.deleteIfExists(path) }
    }
}

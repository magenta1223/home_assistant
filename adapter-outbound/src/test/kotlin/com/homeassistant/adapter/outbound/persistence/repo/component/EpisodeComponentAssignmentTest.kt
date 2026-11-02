package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupRepository
import com.homeassistant.adapter.outbound.persistence.repo.group.ComponentEmbeddingRepository
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.input.component.EpisodeAssignmentOutcome
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.application.port.output.component.*
import com.homeassistant.application.usecase.group.*
import com.homeassistant.application.usecase.component.*
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.group.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class EpisodeComponentAssignmentTest {
    private val space = EmbeddingSpace("test", "passage:v1")
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val nearEast = ComponentVector.normalized(listOf(1.0, 0.2))
    private val user = UserId("owner")
    private val access = EpisodeAccess.restricted(listOf(user))

    @Test fun `extracted components are stored and grouped, and an extracted episode is never processed again`() = withDatabase { db, path ->
        val (boiler, rent) = seedEpisodes(db, "first", "보일러 점검 예약", "월세 이체일은 25일")
        val vectors = mutableMapOf(
            "보일러 점검 예약" to east, "보일러는 매년 가을에 점검한다" to east,
            "월세 이체일은 25일" to north, "월세 이체일은 25일이다" to north,
        )
        val extractor = FakeExtractor(mapOf(
            "보일러 점검 예약" to listOf("보일러는 매년 가을에 점검한다"),
            "월세 이체일은 25일" to listOf("월세 이체일은 25일이다"),
        ))
        val first = service(db, vectors, extractor).execute(10)

        assertEquals(listOf(
            Component(1, "보일러는 매년 가을에 점검한다", boiler, access),
            Component(2, "월세 이체일은 25일이다", rent, access),
        ), ComponentRepository(db).all())
        assertEquals(listOf(listOf(1), listOf(2)), first.episodes.map { (it.outcome as EpisodeAssignmentOutcome.Extracted).componentIds })
        assertEquals(setOf(1, 2), GroupRepository(db).snapshot().groups.flatMap { it.group.componentIds }.toSet())

        seedEpisodes(db, "second", "보일러 수리비 12만 원")
        vectors["보일러 수리비 12만 원"] = nearEast
        val result = service(db, vectors, extractor).execute(10)

        assertEquals(EpisodeAssignmentOutcome.Extracted(emptyList()), result.episodes.single().outcome)
        assertEquals("보일러는 매년 가을에 점검한다", extractor.known.last().first())
        assertEquals(listOf("보일러 수리비 12만 원"), extractor.messages.last())

        val restarted = DatabaseFactory.init(path)
        assertTrue(service(restarted, vectors, extractor).execute(10).episodes.isEmpty())
    }

    @Test fun `a failed write records the attempt and the retry extracts the episode once`() = withDatabase { db, _ ->
        val (episode) = seedEpisodes(db, "first", "화분 물주기")
        val vectors = mapOf("화분 물주기" to east, "화분은 주 1회 물을 준다" to east)
        val extractor = FakeExtractor(mapOf("화분 물주기" to listOf("화분은 주 1회 물을 준다")))
        val failing = service(db, vectors, extractor, store = object : ComponentMaintenanceStore by ComponentRepository(db) {
            override fun saveExtraction(episodeId: Int, statements: List<String>): List<Int> = error("disk full")
        })
        assertIs<EpisodeAssignmentOutcome.Failed>(failing.execute(10).episodes.single().outcome)
        assertEquals(OutboxRow("FAILED", 1, "disk full"), outboxRow(db, episode))

        service(db, vectors, extractor).execute(10)
        assertEquals(1, ComponentRepository(db).all().size)
        assertEquals(OutboxRow("COMPLETED", 2, null), outboxRow(db, episode))
    }

    private class FakeExtractor(private val statements: Map<String, List<String>>) : ComponentKnowledgeExtractor {
        val known = mutableListOf<List<String>>()
        val messages = mutableListOf<List<String>>()

        override fun extract(episode: PendingEpisode, known: List<String>): List<String> {
            this.known.add(known)
            messages.add(episode.messages)
            return statements[episode.content].orEmpty()
        }
    }

    private fun seedEpisodes(db: Database, key: String, vararg contents: String): List<Int> {
        val record = SourceRecordRepositoryImpl(db).saveAll(SourceDescriptor("test", key), listOf(SourceRecordDraft(key, contents.joinToString())),
            access).recordsToAnalyze.single()
        return EpisodeRepository(db).commit(user, contents.mapIndexed { index, content ->
            IdempotentEpisodeProposal("$key-$index", EpisodeProposal("title", content, listOf(record.id)))
        }, listOf(record.id)).map { it.id }
    }

    private fun service(
        db: Database,
        vectors: Map<String, ComponentVector>,
        extractor: ComponentKnowledgeExtractor,
        store: ComponentMaintenanceStore = ComponentRepository(db),
    ): OrganizePendingEpisodesService {
        val groups = GroupRepository(db)
        val texts = object : EpisodeEmbeddingGenerator {
            override val space = this@EpisodeComponentAssignmentTest.space
            override fun isAvailable() = true
            override fun generate(content: String) = vectors.getValue(content)
        }
        val episodeEmbeddings = PrepareEpisodeEmbedding(texts, EpisodeEmbeddingRepository(db))
        val componentEmbeddings = PrepareComponentEmbedding(ComponentStatementVectors(ComponentRepository(db), texts), ComponentEmbeddingRepository(db))
        val settings = GroupPolicySettings()
        return OrganizePendingEpisodesService(
            ComponentAssignmentOutboxRepository(db),
            ExtractComponents(store, episodeEmbeddings, componentEmbeddings, extractor),
            RefreshGroupedComponentEmbeddings(groups, groups, componentEmbeddings),
            OrganizePendingComponentsService(groups, OrganizeComponent(groups, groups, componentEmbeddings,
                GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings)))),
            MergeSmallGroups(groups, groups, GroupMergePolicy(settings)),
            retryDelayMillis = 0,
        )
    }

    private data class OutboxRow(val status: String, val attempts: Int, val lastError: String?)

    private fun outboxRow(db: Database, episodeId: Int): OutboxRow? = transaction(db) {
        ComponentAssignmentOutboxTable.selectAll().where { ComponentAssignmentOutboxTable.episodeId eq episodeId }.singleOrNull()?.let {
            OutboxRow(it[ComponentAssignmentOutboxTable.status], it[ComponentAssignmentOutboxTable.attempts], it[ComponentAssignmentOutboxTable.lastError])
        }
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("episode-component-assignment", ".db")
        try { block(DatabaseFactory.init(path.toString()), path.toString()) }
        finally { Files.deleteIfExists(path) }
    }
}

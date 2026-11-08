package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.adapter.outbound.persistence.repo.embedding.EpisodeEmbeddingRepositoryImpl
import com.homeassistant.domain.embedding.EmbeddingVector
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.embedding.ComponentEmbeddingRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentExtractor
import com.homeassistant.application.port.outbound.memoryextraction.component.PendingEpisode
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.usecase.memoryextraction.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.embedding.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.embedding.PrepareComponentEmbedding
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.group.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.access.AccessControl
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
    private val east = EmbeddingVector.normalized(listOf(1.0, 0.0))
    private val north = EmbeddingVector.normalized(listOf(0.0, 1.0))
    private val nearEast = EmbeddingVector.normalized(listOf(1.0, 0.2))
    private val user = UserId("owner")
    private val access = AccessControl.restricted(listOf(user))

    @Test
    fun `extracted components are stored and grouped, and an extracted episode is never processed again`() = withDatabase { db, path ->
        val (boiler, rent) = seedEpisodes(db, "first", "보일러 점검 예약", "월세 이체일은 25일")
        val vectors = mutableMapOf(
            "보일러 점검 예약" to east, "보일러는 매년 가을에 점검한다" to east,
            "월세 이체일은 25일" to north, "월세 이체일은 25일이다" to north,
        )
        val extractor = FakeExtractor(mapOf(
            "보일러 점검 예약" to listOf("보일러는 매년 가을에 점검한다"),
            "월세 이체일은 25일" to listOf("월세 이체일은 25일이다"),
        ))
        val first = assignmentRound(db, vectors, extractor)

        assertEquals(listOf(
            Component(1, "보일러는 매년 가을에 점검한다", boiler, access),
            Component(2, "월세 이체일은 25일이다", rent, access),
        ), ComponentRepositoryImpl(db).findCovering(access))
        assertEquals(listOf(listOf(1), listOf(2)), first)
        assertEquals(setOf(1, 2), GroupRepositoryImpl(db).findSnapshot().groups.flatMap { it.group.componentIds }.toSet())

        seedEpisodes(db, "second", "보일러 수리비 12만 원")
        vectors["보일러 수리비 12만 원"] = nearEast
        val result = assignmentRound(db, vectors, extractor)

        assertEquals(listOf(emptyList()), result)
        assertEquals("보일러는 매년 가을에 점검한다", extractor.known.last().first())
        assertEquals(listOf("보일러 수리비 12만 원"), extractor.messages.last())

        val restarted = DatabaseFactory.init(path)
        assertTrue(assignmentRound(restarted, vectors, extractor).isEmpty())
    }

    //TODO: restore

//    @Test
//    fun `a failed write records the attempt and the retry extracts the episode once`() = withDatabase { db, _ ->
//        val (episode) = seedEpisodes(db, "first", "화분 물주기")
//        val vectors = mapOf("화분 물주기" to east, "화분은 주 1회 물을 준다" to east)
//        val extractor = FakeExtractor(mapOf("화분 물주기" to listOf("화분은 주 1회 물을 준다")))
//        val failing = service(
//            db,
//            vectors,
//            extractor,
//            store = object : ComponentWriter, ComponentReader by ComponentRepository(db) {
//                override fun writeComponents(episodeId: Int, statements: List<String>): List<Int> = error("disk full")
//            }
//        )
//        assertIs<EpisodeAssignmentOutcome.Failed>(failing.execute(10).episodes.single().outcome)
//        assertEquals(OutboxRow("FAILED", 1, "disk full"), outboxRow(db, episode))
//
//        service(db, vectors, extractor).execute(10)
//        assertEquals(1, ComponentRepository(db).all().size)
//        assertEquals(OutboxRow("COMPLETED", 2, null), outboxRow(db, episode))
//    }

    private class FakeExtractor(private val statements: Map<String, List<String>>) : ComponentExtractor {
        val known = mutableListOf<List<String>>()
        val messages = mutableListOf<List<String>>()

        override fun extract(episode: PendingEpisode, known: List<String>): List<String> {
            this.known.add(known)
            messages.add(episode.messages)
            return statements[episode.content].orEmpty()
        }
    }

    private fun seedEpisodes(db: Database, key: String, vararg contents: String): List<Int> {
        val record = assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(SourceDescriptor("test", key), listOf(SourceRecordDraft(key, contents.joinToString())),
            access)).recordsToAnalyze.single()
        return EpisodeRepositoryImpl(db).save(EpisodeBatch(user, contents.map { content ->
            EpisodeProposal("title", content, listOf(record.id))
        }, listOf(record.id))).map { it.id }
    }

    /** Assigns every claimable episode, completing its outbox row, then places the new Components in Groups. */
    private fun assignmentRound(
        db: Database,
        vectors: Map<String, EmbeddingVector>,
        extractor: ComponentExtractor,
    ): List<List<Int>> {
        val groups = GroupRepositoryImpl(db)
        val store = ComponentRepositoryImpl(db)
        val texts = object : EmbeddingGenerator {
            override val space = this@EpisodeComponentAssignmentTest.space
            override fun isAvailable() = true
            override fun embedDocument(text: String) = vectors.getValue(text)
            override fun embedQuery(text: String) = vectors.getValue(text)
        }
        val episodeEmbeddings = PrepareEpisodeEmbedding(texts, EpisodeEmbeddingRepositoryImpl(db))
        val componentEmbeddings = PrepareComponentEmbedding(texts, ComponentEmbeddingRepositoryImpl(db))
        val settings = GroupPolicySettings()
        val assign = AssignEpisodeToComponents(store, episodeEmbeddings, componentEmbeddings, extractor)
        val outbox = ComponentAssignmentOutboxRepository(db)
        val assigned = outbox.claimReady(10, NOW, NOW, NOW - 1, 5).map { task ->
            assign.assign(task.episode).also { outbox.markCompleted(task.outboxId, task.attempt, NOW) }
        }
        val organizer = OrganizeComponentIntoGroups(
            groups, componentEmbeddings,
            GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings))
        )
        store.findUngroupedAfter(0, 10).forEach(organizer::organize)
        return assigned
    }

    private data class OutboxRow(val status: String, val attempts: Int, val lastError: String?)

    private fun outboxRow(db: Database, episodeId: Int): OutboxRow? = transaction(db) {
        ComponentAssignmentOutboxTable.selectAll().where { ComponentAssignmentOutboxTable.episodeId eq episodeId }.singleOrNull()?.let {
            OutboxRow(it[ComponentAssignmentOutboxTable.status], it[ComponentAssignmentOutboxTable.attempts], it[ComponentAssignmentOutboxTable.lastError])
        }
    }

    private companion object {
        const val NOW = 1_000_000L
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("episode-component-assignment", ".db")
        try { block(DatabaseFactory.init(path.toString()), path.toString()) }
        finally { Files.deleteIfExists(path) }
    }
}

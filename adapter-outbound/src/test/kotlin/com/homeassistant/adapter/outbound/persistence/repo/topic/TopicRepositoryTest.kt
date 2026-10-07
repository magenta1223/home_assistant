package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicRelationTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicMembershipTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicTable
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.topic.TopicRelation
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs

class TopicRepositoryTest {
    @Test
    fun `unplaced recovery survives restart and excludes successful placement`() = withDatabase { db ->
        val first = createMemory(db, "first", MemoryAccess.restricted(listOf(UserId("other"))))
        val second = createMemory(db, "second")
        val repository = TopicRepository(db)
        assertEquals(listOf(first), repository.loadUnplaced(1).map { it.id })
        val batch = placementBatch("recovery", first)
        transaction(db) {
            exec("""
                CREATE TRIGGER fail_membership BEFORE INSERT ON topic_memberships
                BEGIN SELECT RAISE(ABORT, 'forced membership failure'); END
            """.trimIndent())
        }
        assertFails { repository.commitIfUnplaced(setOf(first), batch) }
        transaction(db) { exec("DROP TRIGGER fail_membership") }

        val restarted = TopicRepository(db)
        assertEquals(listOf(first, second), restarted.loadUnplaced(10).map { it.id })
        assertIs<TopicPlacementCommitResult.Placed>(restarted.commitIfUnplaced(setOf(first), batch))
        assertEquals(listOf(second), TopicRepository(db).loadUnplaced(10).map { it.id })
        assertEquals(TopicPlacementCommitResult.AlreadyPlaced, restarted.commitIfUnplaced(setOf(first), batch))
        assertEquals(1L, transaction(db) { TopicTable.selectAll().count() })
    }

    @Test
    fun `overlapping batch is stale without partial writes and candidates are bounded newest first`() = withDatabase { db ->
        val first = createMemory(db, "first")
        val second = createMemory(db, "second")
        val repository = TopicRepository(db)
        repository.commitIfUnplaced(setOf(first), placementBatch("first", first))

        val stale = repository.commitIfUnplaced(setOf(first, second), placementBatch("overlap", first, second))

        assertEquals(TopicPlacementCommitResult.Stale, stale)
        assertEquals(1L, transaction(db) { TopicTable.selectAll().count() })
        assertEquals(listOf(second), repository.loadUnplaced(10).map { it.id })
        val placed = assertIs<TopicPlacementCommitResult.Placed>(
            repository.commitIfUnplaced(setOf(second), placementBatch("second", second)),
        )
        assertEquals(listOf(placed.writeResult.createdTopicIds.getValue("second")), repository.loadCandidates(1).map { it.id })
    }

    @Test
    fun `conditional writer rejects missing memories mismatched coverage and empty scope`() = withDatabase { db ->
        val memoryId = createMemory(db, "one")
        val repository = TopicRepository(db)
        assertFailsWith<IllegalArgumentException> {
            repository.commitIfUnplaced(setOf(memoryId), placementBatch("foreign", memoryId, 999))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.commitIfUnplaced(setOf(memoryId, 999), placementBatch("missing", memoryId, 999))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.commitIfUnplaced(emptySet(), TopicWriteBatch())
        }
        assertFailsWith<IllegalArgumentException> { repository.loadUnplaced(0) }
        assertFailsWith<IllegalArgumentException> { repository.loadCandidates(0) }
        assertEquals(0L, transaction(db) { TopicTable.selectAll().count() })
    }

    @Test
    fun `atomic creation supports grouped multi topic memories reversed links cycles and retry`() = withDatabase { db ->
        val memoryId = createMemory(db, "public")
        val repository = TopicRepository(db)
        val request = TopicWriteBatch(
            creations = listOf(
                TopicCreation("a", "Travel"), TopicCreation("b", "Budget"), TopicCreation("c", "Booking"),
            ),
            memberships = listOf(
                TopicMembershipAddition(created("a"), "Schedule", listOf(memoryId)),
                TopicMembershipAddition(created("a"), "Booking", listOf(memoryId)),
                TopicMembershipAddition(created("b"), "Expenses", listOf(memoryId)),
            ),
            relations = listOf(link("a", "b"), link("b", "a"), link("b", "c"), link("c", "a")),
        )

        val result = repository.apply(request)
        assertEquals(result, repository.apply(request))
        val ids = result.createdTopicIds
        val topics = repository.readForMaintenance(ids.values).associateBy { it.id }
        assertEquals(
            mapOf("Schedule" to listOf(memoryId), "Booking" to listOf(memoryId)),
            topics.getValue(ids.getValue("a")).memories,
        )
        assertEquals(listOf(memoryId), topics.getValue(ids.getValue("b")).memories.getValue("Expenses"))
        val relations = repository.relationsForMaintenance(listOf(ids.getValue("a")))
        assertEquals(
            setOf(
                TopicRelation.between(ids.getValue("a"), ids.getValue("b")),
                TopicRelation.between(ids.getValue("a"), ids.getValue("c")),
            ),
            relations,
        )
        assertEquals(3L, transaction(db) { TopicRelationTable.selectAll().count() })
        assertEquals(3L, transaction(db) { TopicTable.selectAll().count() })
        assertEquals(3L, transaction(db) { TopicMembershipTable.selectAll().count() })
    }

    @Test
    fun `additive changes preserve hidden memberships and exact source memory state`() = withDatabase { db ->
        val hiddenId = createMemory(db, "hidden", MemoryAccess.restricted(listOf(UserId("other"))))
        val visibleId = createMemory(db, "public")
        val repository = TopicRepository(db)
        val sourceRepository = SourceRecordRepositoryImpl(db)
        val memoryRepository = MemoryRepository(db)
        val sourcesBefore = listOf("hidden", "public").associateWith { sourceRepository.findBySource(source(it)) }
        val memoriesBefore = memoryRepository.getMemories(UserId("other"))
        val topicId = repository.apply(TopicWriteBatch(
            creations = listOf(TopicCreation("mixed", "Mixed")),
            memberships = listOf(TopicMembershipAddition(created("mixed"), "Facts", listOf(hiddenId))),
        )).createdTopicIds.getValue("mixed")

        repository.apply(TopicWriteBatch(
            memberships = listOf(TopicMembershipAddition(TopicReference.Existing(topicId), "Facts", listOf(visibleId))),
        ))

        val storedMembers = repository.readForMaintenance(listOf(topicId)).single().memories.getValue("Facts")
        assertEquals(setOf(hiddenId, visibleId), storedMembers.toSet())
        assertEquals(memoriesBefore, memoryRepository.getMemories(UserId("other")))
        assertEquals(
            sourcesBefore,
            listOf("hidden", "public").associateWith { sourceRepository.findBySource(source(it)) },
        )
    }

    @Test
    fun `missing memory or topic and self links roll back creations`() = withDatabase { db ->
        val repository = TopicRepository(db)
        val invalidBatches = listOf(
            TopicWriteBatch(
                creations = listOf(TopicCreation("missing-memory", "Topic")),
                memberships = listOf(TopicMembershipAddition(created("missing-memory"), "Facts", listOf(999))),
            ),
            TopicWriteBatch(
                creations = listOf(TopicCreation("missing-topic", "Topic")),
                relations = listOf(TopicRelationAddition(created("missing-topic"), TopicReference.Existing(999))),
            ),
            TopicWriteBatch(listOf(TopicCreation("self", "Topic")), relations = listOf(link("self", "self"))),
            TopicWriteBatch(
                creations = listOf(TopicCreation("unknown-key", "Topic")),
                relations = listOf(link("unknown-key", "absent")),
            ),
        )
        invalidBatches.forEach { batch -> assertFailsWith<IllegalArgumentException> { repository.apply(batch) } }
        assertEquals(0L, transaction(db) { TopicTable.selectAll().count() })
    }

    @Test
    fun `creation key conflict is explicit and leaves original unchanged`() = withDatabase { db ->
        val repository = TopicRepository(db)
        val id = repository.apply(TopicWriteBatch(
            creations = listOf(TopicCreation("stable", "Original")),
        )).createdTopicIds.getValue("stable")
        assertFailsWith<IllegalArgumentException> {
            repository.apply(TopicWriteBatch(
                creations = listOf(TopicCreation("new", "New"), TopicCreation("stable", "Changed")),
            ))
        }
        assertEquals("Original", repository.readForMaintenance(listOf(id)).single().description)
        assertEquals(1L, transaction(db) { TopicTable.selectAll().count() })
    }

    @Test
    fun `storage failure rolls back topics memberships and relations`() = withDatabase { db ->
        val memoryId = createMemory(db, "public")
        transaction(db) {
            exec("""
                CREATE TRIGGER fail_relation BEFORE INSERT ON topic_relations
                BEGIN SELECT RAISE(ABORT, 'forced relation failure'); END
            """.trimIndent())
        }
        val repository = TopicRepository(db)
        assertFails {
            repository.apply(TopicWriteBatch(
                creations = listOf(TopicCreation("a", "A"), TopicCreation("b", "B")),
                memberships = listOf(TopicMembershipAddition(created("a"), "Facts", listOf(memoryId))),
                relations = listOf(link("a", "b")),
            ))
        }
        assertEquals(0L, transaction(db) { TopicTable.selectAll().count() })
        assertEquals(0L, transaction(db) { TopicMembershipTable.selectAll().count() })
        assertEquals(0L, transaction(db) { TopicRelationTable.selectAll().count() })
        assertTrue(repository.readForMaintenance(emptyList()).isEmpty())
        transaction(db) { exec("DROP TRIGGER fail_relation") }
        val retry = repository.apply(TopicWriteBatch(creations = listOf(TopicCreation("a", "A"))))
        assertTrue(repository.readForMaintenance(retry.createdTopicIds.values).single().memories.isEmpty())
    }

    private fun createMemory(db: Database, name: String, access: MemoryAccess = MemoryAccess.PUBLIC): Int {
        val record = SourceRecordRepositoryImpl(db).saveAll(
            source(name), listOf(SourceRecordDraft(name, "source-$name")), access,
        ).recordsToAnalyze.single()
        return MemoryRepository(db).commit(
            UserId("creator"),
            listOf(IdempotentMemoryProposal(name, MemoryProposal("fact-$name", listOf(record.id)))),
            listOf(record.id),
        ).single().id
    }

    private fun source(name: String) = SourceDescriptor("test", name)
    private fun placementBatch(key: String, vararg ids: Int) = TopicWriteBatch(
        creations = listOf(TopicCreation(key, "Topic $key")),
        memberships = listOf(TopicMembershipAddition(created(key), "Facts", ids.toList())),
    )
    private fun created(key: String) = TopicReference.Created(key)
    private fun link(first: String, second: String) = TopicRelationAddition(created(first), created(second))

    private fun withDatabase(block: (Database) -> Unit) {
        val path = Files.createTempFile("topic-store", ".db")
        try {
            block(DatabaseFactory.init(path.toString()))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

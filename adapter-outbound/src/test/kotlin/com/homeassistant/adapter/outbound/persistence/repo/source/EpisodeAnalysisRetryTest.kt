package com.homeassistant.adapter.outbound.persistence.repo.source

import kotlin.test.assertIs
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.application.port.inbound.memoryextraction.DuplicateSourceRecordsException
import com.homeassistant.application.port.inbound.memoryextraction.SourceAccessConflictException
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisUnavailableException
import com.homeassistant.application.port.inbound.memoryextraction.InvalidAllowedUsersException
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.usecase.memoryextraction.episode.EpisodeAnalysisService
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeExtractor
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import java.nio.file.Files
import org.jetbrains.exposed.sql.Database
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class EpisodeAnalysisRetryTest {
    @Test
    fun `audience conflict reports the existing access scope`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-audience-conflict", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val request = request("same", AccessControl.restricted(listOf(USER_ID)))
            assertIs<SourceRecordSaveResult.Saved>(sourceRecords.saveAll(request.source.source, request.source.records, AccessControl.PUBLIC))

            val failure = assertFailsWith<SourceAccessConflictException> {
                service(sourceRecords, db, RecordingExtractor { emptyList() }, RecordingEpisodeRepository())
                    .execute(request)
            }

            assertEquals(AccessControl.PUBLIC, failure.existingAccess)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `unknown audience user is rejected before source data is stored`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-invalid-audience", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val request = request(
                "restricted",
                AccessControl.restricted(listOf(UserId("unknown-member"))),
            )

            assertFailsWith<InvalidAllowedUsersException> {
                service(sourceRecords, db, RecordingExtractor { emptyList() }, RecordingEpisodeRepository())
                    .execute(request)
            }

            assertEquals(1, resubmit(sourceRecords, request).importedRecordCount)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `renamed cumulative import analyzes only the new records`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-renamed-import", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val original = SourceDescriptor("kakao", "original.txt")
            val initial = assertIs<SourceRecordSaveResult.Saved>(sourceRecords.saveAll(original, listOf(draft("old-1"), draft("old-2"))))
            markAnalyzed(db, initial.recordsToAnalyze.map { it.id })
            val extractor = RecordingExtractor { document ->
                listOf(proposal(document))
            }
            val service = service(sourceRecords, db, extractor, RecordingEpisodeRepository())
            val renamed = original.copy(name = "renamed.txt")

            val result = service.execute(
                EpisodeAnalysisRequest(
                    userId = USER_ID.value,
                    source = SourceDocumentDraft(
                        renamed,
                        listOf(draft("old-1"), draft("old-2"), draft("new")),
                    ),
                ),
            )

            assertEquals(1, result.importedRecordCount)
            assertEquals(2, result.alreadyAnalyzedRecordCount)
            assertEquals(listOf("new"), extractor.documents.single().records.map { it.deduplicationKey })
            assertEquals(1, assertIs<SourceRecordSaveResult.Saved>(sourceRecords.saveAll(renamed, listOf(draft("new")))).alreadyAnalyzedRecordCount)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `episode persistence failure leaves source pending`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-persistence-failure", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val extractor = RecordingExtractor { document ->
                listOf(proposal(document))
            }
            val failure = IllegalStateException("persistence failed")
            val service = service(sourceRecords, db, extractor, RecordingEpisodeRepository(failure))
            val request = request("a")

            val unavailable = assertFailsWith<EpisodeAnalysisUnavailableException> { service.execute(request) }
            assertSame(failure, unavailable.cause)
            assertEquals(1, resubmit(sourceRecords, request).retriedRecordCount)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `extractor failure remains pending then retry succeeds and later upload is duplicate`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-retry", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            var shouldFail = true
            val extractor = RecordingExtractor { document ->
                if (shouldFail) {
                    shouldFail = false
                    error("temporary failure")
                }
                listOf(proposal(document))
            }
            val writer = RecordingEpisodeRepository()
            val service = service(sourceRecords, db, extractor, writer)
            val request = request("a", AccessControl.restricted(listOf(USER_ID)))

            assertFailsWith<EpisodeAnalysisUnavailableException> { service.execute(request) }
            assertEquals(1, resubmit(sourceRecords, request).retriedRecordCount)

            val retried = service.execute(request)
            assertEquals(0, retried.importedRecordCount)
            assertEquals(1, retried.retriedRecordCount)
            assertEquals(0, retried.alreadyAnalyzedRecordCount)
            assertEquals(1, retried.episodeCount)
            assertEquals(Visibility.RESTRICTED, retried.visibility)
            assertEquals(1, resubmit(sourceRecords, request).alreadyAnalyzedRecordCount)

            val duplicate = assertFailsWith<DuplicateSourceRecordsException> { service.execute(request) }
            assertEquals(1, duplicate.recordCount)
            assertEquals(2, extractor.documents.size)
            assertEquals(1, writer.episodes.size)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `partial new upload analyzes pending and new records together and reports counts`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-partial-retry", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val source = SourceDescriptor("test", "source")
            val initial = assertIs<SourceRecordSaveResult.Saved>(sourceRecords.saveAll(source, listOf(draft("pending"), draft("analyzed"))))
            markAnalyzed(db,
                listOf(initial.recordsToAnalyze.single { it.deduplicationKey == "analyzed" }.id),
            )
            val extractor = RecordingExtractor { document ->
                listOf(
                    proposal(document.copy(records = listOf(document.records[0]))),
                    proposal(document.copy(records = listOf(document.records[1]))),
                )
            }
            val service = service(sourceRecords, db, extractor, RecordingEpisodeRepository())

            val result = service.execute(
                EpisodeAnalysisRequest(
                    userId = USER_ID.value,
                    source = SourceDocumentDraft(
                        source,
                        listOf(draft("analyzed"), draft("pending"), draft("new")),
                    ),
                ),
            )

            assertEquals(1, result.importedRecordCount)
            assertEquals(1, result.retriedRecordCount)
            assertEquals(1, result.alreadyAnalyzedRecordCount)
            assertEquals(2, result.episodeCount)
            assertEquals(Visibility.PUBLIC, result.visibility)
            assertEquals(listOf("pending", "new"), extractor.documents.single().records.map { it.deduplicationKey })
            assertEquals(3, assertIs<SourceRecordSaveResult.Saved>(sourceRecords.saveAll(source, listOf(draft("analyzed"), draft("pending"), draft("new")))).alreadyAnalyzedRecordCount)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun service(
        sourceRecords: SourceRecordRepositoryImpl,
        db: Database,
        extractor: EpisodeExtractor,
        writer: RecordingEpisodeRepository,
    ) = EpisodeAnalysisService(
        episodeExtractor = extractor,
        sourceRecords = sourceRecords,
        episodes = object : EpisodeRepository by writer {
            override fun save(batch: EpisodeBatch) = writer.save(batch).also {
                markAnalyzed(db, batch.analyzedSourceRecordIds)
            }
        },
        accessPolicy = { it == USER_ID },
    )

    private fun request(key: String, access: AccessControl = AccessControl.PUBLIC) = EpisodeAnalysisRequest(
        userId = USER_ID.value,
        source = SourceDocumentDraft(SourceDescriptor("test", "source"), listOf(draft(key))),
        access = access,
    )

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")

    /** Submits [request]'s records again; the counts tell whether they are stored, pending or analyzed. */
    private fun resubmit(sourceRecords: SourceRecordRepositoryImpl, request: EpisodeAnalysisRequest) =
        assertIs<SourceRecordSaveResult.Saved>(sourceRecords.saveAll(request.source.source, request.source.records, request.access))

    private fun markAnalyzed(db: Database, recordIds: Collection<Int>) {
        EpisodeRepositoryImpl(db).save(EpisodeBatch(USER_ID, emptyList(), recordIds))
    }

    private fun proposal(document: SourceDocument): EpisodeProposal = EpisodeProposal(
        title = "title",
        content = "episode-${document.records.single().deduplicationKey}",
        evidenceIds = document.records.map { it.id },
    )

    private class RecordingExtractor(
        private val block: suspend (SourceDocument) -> List<EpisodeProposal>,
    ) : EpisodeExtractor {
        val documents = mutableListOf<SourceDocument>()

        override suspend fun analyze(document: SourceDocument): List<EpisodeProposal> {
            documents += document
            return block(document)
        }
    }

    private class RecordingEpisodeRepository(
        private val failure: RuntimeException? = null,
    ) : EpisodeRepository {
        val episodes = mutableListOf<Episode>()

        override fun findVisibleTo(userId: UserId) = episodes.toList()

        override fun save(batch: EpisodeBatch): List<Episode> {
            val (createdBy, proposals) = batch
            failure?.let { throw it }
            val saved = proposals.map { item ->
                Episode(
                    id = episodes.size + 1,
                    createdByUserId = createdBy.value,
                    title = item.title,
                    content = item.content,
                    access = AccessControl.PUBLIC,
                    evidenceRefs = item.evidenceIds,
                    createdAt = (episodes.size + 1) * 1_000L,
                ).also(episodes::add)
            }
            return saved
        }
    }

    private companion object {
        val USER_ID = UserId("member-1")
    }
}

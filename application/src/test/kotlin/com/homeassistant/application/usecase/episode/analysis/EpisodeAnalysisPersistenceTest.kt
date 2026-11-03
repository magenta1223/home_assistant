package com.homeassistant.application.usecase.episode.analysis

import com.homeassistant.application.port.input.episode.analysis.EpisodeAnalysisRequest
import com.homeassistant.application.port.output.episode.EpisodeExtractor
import com.homeassistant.domain.episode.*
import com.homeassistant.application.port.output.source.SourceRecordRepository
import com.homeassistant.application.port.output.source.SourceRecordSaveResult
import com.homeassistant.domain.source.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EpisodeAnalysisPersistenceTest {
    @Test
    fun `analysis completes after canonical commit without component generation`() = runBlocking {
        val events = mutableListOf<String>()
        val source = SourceDescriptor("test", "component")
        val records = object : SourceRecordRepository {
            override fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>, access: EpisodeAccess) =
                SourceRecordSaveResult(
                    listOf(SourceRecord(1, "key", "source", SourceRecordAnalysisStatus.PENDING, access)),
                    1, 0, 0,
                )
            override fun findBySource(source: SourceDescriptor) = emptyList<SourceRecord>()
        }
        val service = EpisodeAnalysisService(
            episodeExtractor = object : EpisodeExtractor {
                override suspend fun analyze(document: SourceDocument) = listOf(EpisodeProposal("title", "fact", listOf(1)))
            },
            sourceRecords = records,
            episodeWriter = { _, _, _ ->
                events += "commit"
                emptyList()
            },
            accessPolicy = { true },
        )

        val result = service.execute(EpisodeAnalysisRequest("user", SourceDocumentDraft(source, listOf(SourceRecordDraft("key", "source")))))

        assertEquals(listOf("commit"), events)
        assertEquals(1, result.episodeCount)
    }
}

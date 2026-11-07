package com.homeassistant.application.usecase.episode.analysis

import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeExtractor
import com.homeassistant.domain.access.*
import com.homeassistant.domain.episode.*
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.application.usecase.memoryextraction.episode.EpisodeAnalysisService
import com.homeassistant.domain.source.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EpisodeAnalysisPersistenceTest {
    @Test
    fun `analysis completes after canonical commit without component generation`() = runBlocking {
        val events = mutableListOf<String>()
        val source = SourceDescriptor("test", "component")
        val records = object : SourceRecordRepository {
            override fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>, access: AccessControl) =
                SourceRecordSaveResult(
                    listOf(SourceRecord(1, "key", "source", SourceRecordAnalysisStatus.PENDING, access)),
                    1, 0, 0,
                )
        }
        val service = EpisodeAnalysisService(
            episodeExtractor = object : EpisodeExtractor {
                override suspend fun analyze(document: SourceDocument) =
                    listOf(EpisodeProposal("title", "fact", listOf(1)))
            },
            sourceRecords = records,
            episodes = object : EpisodeRepository {
                override fun findVisibleTo(userId: UserId) = error("analysis never reads episodes")
                override fun save(batch: EpisodeBatch): List<Episode> {
                    events += "commit"
                    return emptyList()
                }
            },
            accessPolicy = { true },
        )

        val result = service.execute(EpisodeAnalysisRequest("user", SourceDocumentDraft(source, listOf(SourceRecordDraft("key", "source")))))

        assertEquals(listOf("commit"), events)
        assertEquals(1, result.episodeCount)
    }
}

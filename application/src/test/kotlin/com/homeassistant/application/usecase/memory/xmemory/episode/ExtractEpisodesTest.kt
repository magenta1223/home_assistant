package com.homeassistant.application.usecase.memory.xmemory.episode

import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractionClaim
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractor
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeRepository
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.xmemory.episode.Episode
import com.homeassistant.domain.memory.xmemory.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtractEpisodesTest {
    @Test
    fun `the claimed document's Episodes are stored as the submitter's with every claimed record`() = runBlocking<Unit> {
        val claim = EpisodeExtractionClaim("claim", SUBMITTER, SourceDocument(SOURCE, listOf(record(1), record(2))))
        val extractor = RecordingExtractor(listOf(EpisodeProposal("title", "content", listOf(1))))
        val episodes = RecordingEpisodeRepository()

        val stored = ExtractEpisodes(extractor, episodes).extract(claim)

        assertEquals(listOf(claim.document), extractor.documents)
        assertEquals(listOf(EpisodeBatch(SUBMITTER, extractor.proposals, listOf(1, 2))), episodes.batches)
        assertEquals(listOf(1), stored.map { it.id })
    }

    @Test
    fun `records holding no Episode are still stored as extracted`() = runBlocking<Unit> {
        val claim = EpisodeExtractionClaim("claim", SUBMITTER, SourceDocument(SOURCE, listOf(record(1))))
        val episodes = RecordingEpisodeRepository()

        val stored = ExtractEpisodes(RecordingExtractor(emptyList()), episodes).extract(claim)

        assertEquals(emptyList(), stored)
        assertEquals(listOf(EpisodeBatch(SUBMITTER, emptyList(), listOf(1))), episodes.batches)
    }

    private class RecordingExtractor(val proposals: List<EpisodeProposal>) : EpisodeExtractor {
        val documents = mutableListOf<SourceDocument>()

        override suspend fun analyze(document: SourceDocument): List<EpisodeProposal> {
            documents += document
            return proposals
        }
    }

    private class RecordingEpisodeRepository : EpisodeRepository {
        val batches = mutableListOf<EpisodeBatch>()

        override fun findVisibleTo(userId: UserId) = error("extraction never searches Episodes")

        override fun save(batch: EpisodeBatch): List<Episode> {
            batches += batch
            return batch.proposals.mapIndexed { index, proposal ->
                Episode(index + 1, batch.createdBy.value, proposal.title, proposal.content, AccessControl.PUBLIC, proposal.evidenceIds, 0)
            }
        }
    }

    private companion object {
        val SUBMITTER = UserId("member")
        val SOURCE = SourceDescriptor("test", "chat")

        fun record(id: Int) = SourceRecord(id, "record $id", "message $id", AccessControl.PUBLIC)
    }
}

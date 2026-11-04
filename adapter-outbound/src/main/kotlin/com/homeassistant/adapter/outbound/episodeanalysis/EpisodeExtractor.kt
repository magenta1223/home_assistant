package com.homeassistant.adapter.outbound.episodeanalysis

import com.homeassistant.application.port.outbound.episode.EpisodeExtractor
import com.homeassistant.llm.CompletionClient
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Runs model episode analysis for normalized source documents. */
internal class EpisodeExtractor(
    private val client: CompletionClient,
    private val chunkSize: Int = CHUNK_SIZE,
    private val chunkOverlap: Int = minOf(CHUNK_OVERLAP, chunkSize - 1),
    private val maxConcurrentChunks: Int = MAX_CONCURRENT_CHUNKS,
) : EpisodeExtractor {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(chunkOverlap in 0 until chunkSize) { "chunkOverlap must be between zero and chunkSize" }
        require(maxConcurrentChunks > 0) { "maxConcurrentChunks must be positive" }
    }

    override suspend fun analyze(document: SourceDocument): List<EpisodeProposal> {
        val semaphore = Semaphore(maxConcurrentChunks)
        return coroutineScope {
            chunkDocument(document)
                .map { chunk -> async { semaphore.withPermit { analyzeChunk(chunk) } } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun analyzeChunk(document: SourceDocument): List<EpisodeProposal> {
        val raw = client.complete(
            system = EpisodeAnalysisPrompt.system(),
            userMessage = renderDocument(document),
            outputSchema = EpisodeAnalysisOutputContract.schema,
        )
        return EpisodeAnalysisOutputContract.decode(raw).episodes.map { episode ->
            val evidence = parseEvidence(document, episode.evidenceRecordIds)
            require(episode.title.isNotBlank()) { "Episode title must not be blank" }
            require(episode.text.isNotBlank()) { "Episode text must not be blank" }
            require(evidence.isNotEmpty()) { "Episode must include at least one evidence record" }
            EpisodeProposal(
                title = episode.title.trim(),
                content = episode.text.trim(),
                evidenceIds = evidence.map { it.id },
            )
        }
    }

    private fun chunkDocument(document: SourceDocument): List<SourceDocument> {
        val step = chunkSize - chunkOverlap
        return generateSequence(0) { it + step }
            .takeWhile { start ->
                start < document.records.size && (start == 0 || start + chunkOverlap < document.records.size)
            }
            .map { start ->
                document.copy(
                    records = document.records.subList(start, minOf(start + chunkSize, document.records.size)),
                )
            }
            .toList()
    }

    private fun parseEvidence(document: SourceDocument, evidenceRecordIds: List<String>): List<SourceRecord> =
        evidenceRecordIds.map { recordId ->
            document.records.firstOrNull { it.promptId == recordId }
                ?: throw IllegalArgumentException("Unknown evidence record id: $recordId")
        }.distinctBy { it.id }

    private fun renderDocument(document: SourceDocument): String = EpisodeAnalysisInput(
        records = document.records.map { it.toInput() },
    ).encodeToString()

    private companion object {
        const val CHUNK_SIZE = 400
        const val CHUNK_OVERLAP = 20
        const val MAX_CONCURRENT_CHUNKS = 4
    }
}

private fun SourceRecord.toInput() =
    EpisodeAnalysisInputRecord(promptId, content)

private val SourceRecord.promptId: String
    get() = "r$id"

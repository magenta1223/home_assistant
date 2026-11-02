package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.application.port.output.memory.analysis.MemoryExtractor
import com.homeassistant.llm.completion.CompletionClient
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Runs model memory analysis for normalized source documents. */
internal class MemoryExtractor(
    private val client: CompletionClient,
    private val chunkSize: Int = CHUNK_SIZE,
    private val chunkOverlap: Int = minOf(CHUNK_OVERLAP, chunkSize - 1),
    private val maxConcurrentChunks: Int = MAX_CONCURRENT_CHUNKS,
) : MemoryExtractor {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(chunkOverlap in 0 until chunkSize) { "chunkOverlap must be between zero and chunkSize" }
        require(maxConcurrentChunks > 0) { "maxConcurrentChunks must be positive" }
    }

    override suspend fun analyze(document: SourceDocument): List<MemoryProposal> {
        val semaphore = Semaphore(maxConcurrentChunks)
        return coroutineScope {
            chunkDocument(document)
                .map { chunk -> async { semaphore.withPermit { analyzeChunk(chunk) } } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun analyzeChunk(document: SourceDocument): List<MemoryProposal> {
        val raw = client.complete(
            system = MemoryAnalysisPrompt.system(),
            userMessage = renderDocument(document),
            outputSchema = MemoryAnalysisOutputContract.schema,
        )
        return MemoryAnalysisOutputContract.decode(raw).memories.map { memory ->
            val evidence = parseEvidence(document, memory.evidenceRecordIds)
            require(memory.text.isNotBlank()) { "Memory text must not be blank" }
            require(evidence.isNotEmpty()) { "Memory must include at least one evidence record" }
            MemoryProposal(
                content = memory.text.trim(),
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

    private fun renderDocument(document: SourceDocument): String = MemoryAnalysisInput(
        records = document.records.map { it.toInput() },
    ).encodeToString()

    private companion object {
        const val CHUNK_SIZE = 400
        const val CHUNK_OVERLAP = 20
        const val MAX_CONCURRENT_CHUNKS = 4
    }
}

private fun SourceRecord.toInput() =
    MemoryAnalysisInputRecord(promptId, content)

private val SourceRecord.promptId: String
    get() = "r$id"

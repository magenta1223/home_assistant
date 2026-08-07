package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.adapter.outbound.codex.CodexCompletionClient
import com.homeassistant.application.memory.analysis.MemoryExtractionException
import com.homeassistant.application.memory.analysis.MemoryExtractor
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Runs Codex memory analysis for normalized source documents. */
internal class CodexMemoryExtractor(
    private val client: CodexCompletionClient,
    private val chunkSize: Int = CHUNK_SIZE,
) : MemoryExtractor {
    override suspend fun analyze(document: SourceDocument): List<MemoryProposal> {
        if (document.records.isEmpty()) return emptyList()
        val memories = if (document.records.size <= chunkSize) {
            analyzeChunk(document)
        } else {
            val chunkMemories = coroutineScope {
                chunkDocument(document)
                    .map { chunk -> async { analyzeChunk(chunk) } }
                    .awaitAll()
                    .flatten()
            }
            mergeMemories(document, chunkMemories)
        }
        return memories.distinctBy { it.content to it.evidenceIds.toSet() }
    }

    private suspend fun analyzeChunk(document: SourceDocument): List<MemoryProposal> =
        requestMemories(
            document = document,
            system = MemoryAnalysisPrompt.system(),
            userMessage = renderDocument(document),
        )

    private suspend fun mergeMemories(
        document: SourceDocument,
        chunkMemories: List<MemoryProposal>,
    ): List<MemoryProposal> =
        requestMemories(
            document = document,
            system = MemoryAnalysisPrompt.mergeSystem(),
            userMessage = renderMemoryProposals(document, chunkMemories),
        )

    private suspend fun requestMemories(
        document: SourceDocument,
        system: String,
        userMessage: String,
    ): List<MemoryProposal> {
        val raw = client.complete(
            system = system,
            userMessage = userMessage,
            outputSchema = MemoryAnalysisOutputContract.schema,
        )
        val response = MemoryAnalysisOutputContract.decode(raw)
        return response.memories.map { memory ->
            val evidence = parseEvidence(document, memory.evidenceRecordIds)
            if (memory.text.isBlank()) throw MemoryExtractionException("Memory text must not be blank")
            if (memory.subject.isBlank()) throw MemoryExtractionException("Memory subject must not be blank")
            if (evidence.isEmpty()) throw MemoryExtractionException("Memory must include at least one evidence record")
            MemoryProposal(
                content = memory.text.trim(),
                subject = memory.subject.trim(),
                memoryType = memory.memoryType,
                certainty = memory.certainty,
                evidenceIds = evidence.map { it.id },
            )
        }
    }

    private fun chunkDocument(document: SourceDocument): List<SourceDocument> =
        document.records.chunked(chunkSize).map { records -> document.copy(records = records) }

    private fun parseEvidence(document: SourceDocument, evidenceRecordIds: List<String>): List<SourceRecord> =
        evidenceRecordIds.map { recordId ->
            document.records.firstOrNull { it.promptId == recordId }
                ?: throw MemoryExtractionException("Unknown evidence record id: $recordId")
        }.distinctBy { it.id }

    private fun renderDocument(document: SourceDocument): String =
        document.records.joinToString("\n") { "${it.promptId} | ${it.content}" }

    private fun renderMemoryProposals(
        document: SourceDocument,
        memories: List<MemoryProposal>,
    ): String = MemoryAnalysisLlmResponse(
        memories = memories.map { memory ->
            MemoryLlmResponse(
                text = memory.content,
                subject = memory.subject,
                memoryType = memory.memoryType,
                certainty = memory.certainty,
                evidenceRecordIds = memory.evidenceIds.map { evidenceId ->
                    document.records.first { it.id == evidenceId }.promptId
                },
            )
        },
    ).encodeToString()

    private companion object {
        const val CHUNK_SIZE = 200
    }
}

private val SourceRecord.promptId: String
    get() = "r$id"
